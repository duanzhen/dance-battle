#!/usr/bin/env bash
# =============================================================================
# run.sh - dance-battle(无败街舞赛事系统)一键部署脚本
#
# 部署策略(自动降级):
#   方式 1: 从阿里云个人容器镜像仓库拉取镜像并 docker compose 启动
#   方式 2: 阿里云不可用 -> GHCR (ghcr.io/duanzhen/dance-battle), mysql/redis 用 Docker Hub 官方镜像
#   方式 3: 仍然失败 -> 本地构建镜像后运行
#
# 首次运行会自动安装 Docker:
#   - Linux : Docker 官方脚本(优先阿里云镜像源), 需要 sudo/root
#   - macOS : 通过 Homebrew 安装 Docker Desktop
#
# 镜像地址均可通过环境变量覆盖, 例如:
#   APP_IMAGE_ALIYUN=my.registry/app:latest ./run.sh
# =============================================================================

set -uo pipefail

# ---------------------------------------------------------------------------
# 镜像配置
# ---------------------------------------------------------------------------
ALIYUN_REGISTRY="${ALIYUN_REGISTRY:-crpi-9o335a19vfah6d7c.cn-hangzhou.personal.cr.aliyuncs.com/dance_battel}"

# 方式 1: 阿里云个人仓库
APP_IMAGE_ALIYUN="${APP_IMAGE_ALIYUN:-${ALIYUN_REGISTRY}/app:latest}"
MYSQL_IMAGE_ALIYUN="${MYSQL_IMAGE_ALIYUN:-${ALIYUN_REGISTRY}/mysql:8.0}"
REDIS_IMAGE_ALIYUN="${REDIS_IMAGE_ALIYUN:-${ALIYUN_REGISTRY}/redis:7-alpine}"

# 方式 2: GHCR 兜底(app 镜像), mysql/redis 用 Docker Hub 官方镜像
APP_IMAGE_GHCR="${APP_IMAGE_GHCR:-ghcr.io/duanzhen/dance-battle:latest}"
MYSQL_IMAGE_HUB="${MYSQL_IMAGE_HUB:-mysql:8.0}"
REDIS_IMAGE_HUB="${REDIS_IMAGE_HUB:-redis:7-alpine}"

# 方式 3: 本地构建 app 镜像的名称
APP_IMAGE_LOCAL="${APP_IMAGE_LOCAL:-dance-game-app:latest}"

# ---------------------------------------------------------------------------
# 全局状态
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OS=""
SUDO=""       # docker 命令前缀; 非 docker 组用户需要 sudo -E 保留环境变量
SUDO_ROOT=""  # 系统级命令前缀(安装/启动服务)
COMPOSE_CMD=""
SERVER_PORT="80"
TMP_SCRIPT=""

trap 'if [ -n "${TMP_SCRIPT:-}" ]; then rm -f "$TMP_SCRIPT"; fi' EXIT

# ---------------------------------------------------------------------------
# 基础工具函数
# ---------------------------------------------------------------------------
info() { printf '\033[1;32m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

detect_os() {
    case "$(uname -s)" in
        Linux)  OS="linux" ;;
        Darwin) OS="macos" ;;
        *) die "不支持的操作系统: $(uname -s)" ;;
    esac
    info "操作系统: ${OS} ($(uname -m))"
}

setup_sudo() {
    SUDO=""
    SUDO_ROOT=""
    if [ "$(id -u)" -ne 0 ]; then
        if command -v sudo >/dev/null 2>&1; then
            SUDO_ROOT="sudo"
            if [ "$OS" = "linux" ] && ! id -nG 2>/dev/null | grep -qw docker; then
                # -E: 保留 APP_IMAGE 等环境变量, 否则 sudo 默认会清空
                SUDO="sudo -E"
            fi
        fi
    fi
}

# ---------------------------------------------------------------------------
# Docker 安装与守护进程
# ---------------------------------------------------------------------------
ensure_daemon() {
    local tries="${1:-60}" i=0 started=0
    while [ "$i" -lt "$tries" ]; do
        if $SUDO docker info >/dev/null 2>&1; then
            info "Docker daemon 运行正常"
            return 0
        fi
        if [ "$started" -eq 0 ] && [ "$i" -eq 2 ]; then
            started=1
            start_daemon
        fi
        sleep 2
        i=$((i + 1))
    done
    warn "Docker daemon 未能在 $((tries * 2)) 秒内就绪"
    return 1
}

start_daemon() {
    case "$OS" in
        linux)
            if grep -qi "microsoft" /proc/version 2>/dev/null; then
                warn "检测到 WSL 环境, 请在 Windows 侧启动 Docker Desktop 并开启 WSL 集成, 或手动启动 dockerd"
                return 0
            fi
            if command -v systemctl >/dev/null 2>&1; then
                $SUDO_ROOT systemctl enable --now docker >/dev/null 2>&1 || $SUDO_ROOT systemctl start docker >/dev/null 2>&1
            elif command -v service >/dev/null 2>&1; then
                $SUDO_ROOT service docker start >/dev/null 2>&1
            elif command -v rc-service >/dev/null 2>&1; then
                $SUDO_ROOT rc-service docker start >/dev/null 2>&1
            else
                warn "未能自动启动 docker daemon, 请手动启动后重试"
            fi
            ;;
        macos)
            if [ -d "/Applications/Docker.app" ]; then
                open -a Docker 2>/dev/null || true
            else
                warn "未找到 Docker Desktop, 请手动启动"
            fi
            ;;
    esac
}

ensure_docker() {
    if command -v docker >/dev/null 2>&1; then
        info "Docker 已安装: $(docker --version 2>/dev/null || true)"
        ensure_daemon || die "Docker daemon 无法启动, 请手动启动 Docker 后重试"
        return 0
    fi

    warn "未检测到 Docker, 开始自动安装..."
    case "$OS" in
        linux) install_docker_linux ;;
        macos) install_docker_macos ;;
    esac

    if ! command -v docker >/dev/null 2>&1; then
        die "Docker 安装失败, 请手动安装后重试: https://docs.docker.com/engine/install/"
    fi
    ensure_daemon || die "Docker 安装完成但 daemon 未启动, 请检查后重试"
}

install_docker_linux() {
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        die "需要 curl 或 wget 才能安装 Docker, 请先安装"
    fi
    TMP_SCRIPT="$(mktemp)"
    info "下载 Docker 官方安装脚本..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 15 https://get.docker.com -o "$TMP_SCRIPT" \
            || die "下载 https://get.docker.com 失败, 请检查网络"
    else
        wget -q --timeout=15 -O "$TMP_SCRIPT" https://get.docker.com \
            || die "下载 https://get.docker.com 失败, 请检查网络"
    fi
    info "执行 Docker 安装(优先阿里云镜像源)..."
    if ! $SUDO_ROOT sh "$TMP_SCRIPT" --mirror Aliyun; then
        warn "阿里云镜像源安装失败, 尝试默认源..."
        $SUDO_ROOT sh "$TMP_SCRIPT" || die "Docker 安装脚本执行失败, 请手动安装"
    fi
    if [ "$(id -u)" -ne 0 ]; then
        $SUDO_ROOT usermod -aG docker "$(id -un)" 2>/dev/null || true
        warn "已将当前用户加入 docker 组(重新登录后可不加 sudo 使用 docker)"
    fi
}

install_docker_macos() {
    if ! command -v brew >/dev/null 2>&1; then
        local hs=""
        warn "未检测到 Homebrew, 正在安装 Homebrew(可能需要输入 sudo 密码)..."
        hs="$(mktemp)"
        curl -fsSL --connect-timeout 15 https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o "$hs" \
            || die "Homebrew 安装脚本下载失败, 请手动安装: https://brew.sh"
        /bin/bash "$hs" || die "Homebrew 安装失败, 请手动安装: https://brew.sh"
        rm -f "$hs"
        if [ -x "/opt/homebrew/bin/brew" ]; then
            export PATH="/opt/homebrew/bin:$PATH"
        elif [ -x "/usr/local/bin/brew" ]; then
            export PATH="/usr/local/bin:$PATH"
        fi
    fi
    info "通过 Homebrew 安装 Docker Desktop..."
    brew install --cask docker || die "Docker Desktop 安装失败, 请手动安装: https://www.docker.com/products/docker-desktop/"
    # Docker Desktop 的 CLI 可能不在 PATH, 补充进去
    export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
    if ! command -v docker >/dev/null 2>&1; then
        die "docker 命令仍不可用, 请手动打开 Docker Desktop 后重试"
    fi
    info "启动 Docker Desktop..."
    open -a Docker 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Docker Compose
# ---------------------------------------------------------------------------
ensure_compose() {
    if $SUDO docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
        info "Docker Compose: $($SUDO docker compose version 2>/dev/null | head -1)"
        return 0
    fi
    if command -v docker-compose >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
        info "Docker Compose: $(docker-compose version 2>/dev/null | head -1)"
        return 0
    fi

    warn "未检测到 Docker Compose, 尝试安装..."
    case "$OS" in
        linux) install_compose_linux || true ;;
        macos)
            brew install docker-compose >/dev/null 2>&1 \
                || die "docker-compose 安装失败, 请手动安装: https://docs.docker.com/compose/install/"
            ;;
    esac

    if $SUDO docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
    else
        die "Docker Compose 不可用, 请手动安装 docker compose 插件后重试"
    fi
    info "Docker Compose 安装完成"
}

install_compose_linux() {
    # 优先通过系统包安装 compose 插件
    if command -v apt-get >/dev/null 2>&1; then
        $SUDO_ROOT apt-get update -y >/dev/null 2>&1 || true
        $SUDO_ROOT apt-get install -y docker-compose-plugin >/dev/null 2>&1 && return 0
        $SUDO_ROOT apt-get install -y docker-compose-v2 >/dev/null 2>&1 && return 0
    elif command -v dnf >/dev/null 2>&1; then
        $SUDO_ROOT dnf install -y docker-compose-plugin >/dev/null 2>&1 && return 0
    elif command -v yum >/dev/null 2>&1; then
        $SUDO_ROOT yum install -y docker-compose-plugin >/dev/null 2>&1 && return 0
    fi

    # 兜底: 下载官方 docker compose 二进制(cli 插件)
    if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
        local url="https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)"
        local tmp
        tmp="$(mktemp)"
        info "从 GitHub 下载 docker compose 插件: ${url}"
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL --connect-timeout 15 -o "$tmp" "$url" || { rm -f "$tmp"; warn "GitHub 下载失败"; return 1; }
        else
            wget -q --timeout=15 -O "$tmp" "$url" || { rm -f "$tmp"; warn "GitHub 下载失败"; return 1; }
        fi
        $SUDO_ROOT mkdir -p /usr/local/lib/docker/cli-plugins
        $SUDO_ROOT install -m 0755 "$tmp" /usr/local/lib/docker/cli-plugins/docker-compose
        rm -f "$tmp"
    fi
}

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------
get_env() {
    local key="$1" default="$2" val
    val="$(grep -E "^${key}=" .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"//' -e 's/"$//')"
    if [ -z "$val" ]; then
        echo "$default"
    else
        echo "$val"
    fi
}

# 密码强度: 至少 8 位, 且同时包含数字和字母
password_ok() {
    local p="$1"
    [ "${#p}" -ge 8 ] || return 1
    printf '%s' "$p" | grep -q '[0-9]' || return 1
    printf '%s' "$p" | grep -q '[A-Za-z]' || return 1
    return 0
}

# 写入 .env 的某个键值(不破坏其它配置, 密码含特殊字符也安全)
set_env() {
    local key="$1" value="$2" file=".env" tmp
    if [ -f "$file" ]; then
        tmp="$(mktemp)"
        awk -v k="$key" -v v="$value" \
            'BEGIN { FS = OFS = "="; found = 0 }
             $1 == k { $0 = k "=" v; found = 1 }
             { print }
             END { if (!found) print k "=" v }' "$file" > "$tmp" \
            && mv "$tmp" "$file"
    else
        printf '%s=%s\n' "$key" "$value" > "$file"
    fi
}

# 若某个密码仍为默认值或强度不足, 强制用户设置新的强密码
ensure_strong_password() {
    local key="$1" desc="$2" current new
    current="$(get_env "$key" "")"
    if password_ok "$current"; then
        return 0
    fi

    warn "当前 ${key} 为默认值或强度不足(至少 8 位且需包含数字和字母), 需要重新设置"

    # 非交互场景: 通过同名环境变量提供
    new="${!key:-}"
    if [ -n "$new" ]; then
        if password_ok "$new"; then
            set_env "$key" "$new"
            info "已使用环境变量更新 ${key}"
            return 0
        fi
        die "环境变量 ${key} 不符合强度要求(至少 8 位, 且同时包含数字和字母)"
    fi

    if [ ! -t 0 ]; then
        die "当前为非交互环境, 请通过环境变量 ${key} 提供至少 8 位、含数字和字母的密码"
    fi

    while :; do
        printf '请输入新的%s (至少 8 位, 需包含数字和字母): ' "$desc"
        if ! read -rs new; then
            printf '\n'
            die "未输入密码, 已中止"
        fi
        printf '\n'
        if password_ok "$new"; then
            break
        fi
        warn "密码不符合要求: 至少 8 位且同时包含数字和字母, 请重新输入"
    done

    set_env "$key" "$new"
    info "已更新 ${key}(写入 .env)"
}

prepare_env() {
    if [ ! -f .env ]; then
        if [ -f .env.example ]; then
            cp .env.example .env
            info "已根据 .env.example 生成 .env"
        else
            warn "未找到 .env / .env.example, 将使用默认环境变量"
        fi
    fi
    ensure_strong_password LOGIN_PASSWORD "登录密码"
    SERVER_PORT="$(get_env SERVER_PORT 80)"
}

validate_compose() {
    if ! $SUDO $COMPOSE_CMD config -q >/dev/null 2>&1; then
        die "docker-compose.yml 校验失败, 请检查文件是否正确"
    fi
}

# ---------------------------------------------------------------------------
# 部署策略
# ---------------------------------------------------------------------------
wait_for_http() {
    local url="$1" tries="${2:-90}" i=0
    while [ "$i" -lt "$tries" ]; do
        if command -v curl >/dev/null 2>&1; then
            if curl -fsS -o /dev/null --connect-timeout 3 --max-time 5 "$url" 2>/dev/null; then
                return 0
            fi
        elif command -v wget >/dev/null 2>&1; then
            if wget -q -O /dev/null --timeout=5 "$url" 2>/dev/null; then
                return 0
            fi
        fi
        sleep 2
        i=$((i + 1))
    done
    return 1
}

try_deploy() {
    local app_image="$1" mysql_image="$2" redis_image="$3" label="$4"
    info ""
    info "========== 方式 ${label} =========="
    info "app   : ${app_image}"
    info "mysql : ${mysql_image}"
    info "redis : ${redis_image}"

    export APP_IMAGE="$app_image"
    export MYSQL_IMAGE="$mysql_image"
    export REDIS_IMAGE="$redis_image"

    info "拉取镜像..."
    if ! $SUDO $COMPOSE_CMD pull; then
        warn "镜像拉取未完全成功(可能已有本地镜像), 继续尝试启动..."
    fi

    if ! $SUDO $COMPOSE_CMD up -d --no-build --remove-orphans; then
        warn "docker compose 启动失败"
        return 1
    fi

    info "容器已启动, 等待应用就绪(http://127.0.0.1:${SERVER_PORT}/)..."
    if ! wait_for_http "http://127.0.0.1:${SERVER_PORT}/" 90; then
        warn "应用就绪检查超时, 可查看日志: $SUDO $COMPOSE_CMD logs -f app"
        return 1
    fi
    return 0
}

build_and_deploy() {
    info ""
    info "========== 方式 3: 本地构建镜像 =========="
    export APP_IMAGE="$APP_IMAGE_LOCAL"
    export MYSQL_IMAGE="$MYSQL_IMAGE_HUB"
    export REDIS_IMAGE="$REDIS_IMAGE_HUB"

    info "拉取基础镜像 mysql/redis..."
    if ! $SUDO $COMPOSE_CMD pull mysql redis; then
        warn "mysql/redis 镜像拉取未完全成功, 启动时会再次尝试"
    fi

    info "开始构建应用镜像(首次构建需要下载依赖, 耗时较长)..."
    if ! $SUDO $COMPOSE_CMD up -d --build --remove-orphans; then
        warn "本地构建/启动失败"
        return 1
    fi

    info "容器已启动, 等待应用就绪..."
    if ! wait_for_http "http://127.0.0.1:${SERVER_PORT}/" 90; then
        warn "应用就绪检查超时, 可查看日志: $SUDO $COMPOSE_CMD logs -f app"
        return 1
    fi
    return 0
}

print_success() {
    local user pass mpass
    user="$(get_env LOGIN_USERNAME admin)"
    pass="$(get_env LOGIN_PASSWORD 123456)"
    mpass="$(get_env MYSQL_PASSWORD password)"
    info ""
    info "================================================================"
    info "部署成功!"
    info "  访问地址  : http://localhost:${SERVER_PORT}"
    info "  登录账号  : ${user}"
    info "  登录密码  : ${pass}"
    info "  MySQL密码 : ${mpass}"
    info ""
    info "  查看日志  : $SUDO $COMPOSE_CMD logs -f app"
    info "  停止服务  : $SUDO $COMPOSE_CMD down"
    info "================================================================"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
    cd "$SCRIPT_DIR"
    detect_os
    setup_sudo
    ensure_docker
    ensure_compose
    prepare_env
    validate_compose

    if try_deploy "$APP_IMAGE_ALIYUN" "$MYSQL_IMAGE_ALIYUN" "$REDIS_IMAGE_ALIYUN" "1: 阿里云镜像"; then
        print_success
        exit 0
    fi

    # GHCR 兜底: app 从 ghcr.io/duanzhen/dance-battle 获取, mysql/redis 用 Docker Hub 官方镜像
    if try_deploy "$APP_IMAGE_GHCR" "$MYSQL_IMAGE_HUB" "$REDIS_IMAGE_HUB" "2: GHCR (${APP_IMAGE_GHCR})"; then
        print_success
        exit 0
    fi

    if build_and_deploy; then
        print_success
        exit 0
    fi

    die "三种部署方式均失败, 请检查网络与日志后重试: $SUDO $COMPOSE_CMD logs -f app"
}

main
