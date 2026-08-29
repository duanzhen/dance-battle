#!/usr/bin/env sh
# =============================================================================
# install.sh - dance-battle(无败街舞赛事系统)一键部署脚本
#
# 一行安装命令:
#   curl -fsSL https://dance-battel.oss-cn-hangzhou.aliyuncs.com/install.sh | sh
#
# 脚本自动完成:
#   1. 当前目录缺少部署文件时, 由脚本直接生成 docker-compose.yml / .env
#      到 INSTALL_DIR(默认 ~/dance-battle), 不下载任何文件也不拉代码
#   2. 首次运行自动安装 Docker:
#      - Linux : 官方 get.docker.com 脚本, 优先阿里云镜像源(--mirror Aliyun),
#                失败回退 DaoCloud 脚本与默认源; 需要 sudo/root
#      - macOS : 通过 Homebrew 安装 Docker Desktop
#   3. 按顺序部署(自动降级):
#      方式 1: 阿里云容器镜像仓库(ALIYUN_REGISTRY, 国内优先)
#      方式 2: GHCR (ghcr.io/duanzhen/dance-battle), mysql/redis 用 Docker Hub 官方镜像
#      方式 3: 本地构建镜像后运行
#
# 可通过环境变量覆盖, 例如:
#   INSTALL_DIR=/opt/dance-battle REPO_URL=https://gitee.com/xxx/dance-battle.git ./install.sh
#   APP_IMAGE_ALIYUN=my.registry/app:0.1.1 ./install.sh
# =============================================================================

set -u

# ---------------------------------------------------------------------------
# 项目与镜像配置
# ---------------------------------------------------------------------------
# 自动下载项目时使用的仓库与分支(仅一行管道运行时需要, 项目目录内运行可忽略)
REPO_URL="${REPO_URL:-https://github.com/duanzhen/dance-battle.git}"
REPO_BRANCH="${REPO_BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-${HOME:-$PWD}/dance-battle}"

# 方式 1: 阿里云个人仓库(国内优先)
ALIYUN_REGISTRY="${ALIYUN_REGISTRY:-crpi-9o335a19vfah6d7c.cn-hangzhou.personal.cr.aliyuncs.com/dance_battel}"
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
PROJECT_DIR=""
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
# 项目自举: 一行管道运行时自动下载项目文件
# ---------------------------------------------------------------------------
have_curl() { command -v curl >/dev/null 2>&1; }
have_wget() { command -v wget >/dev/null 2>&1; }

# 下载到 stdout, 或 -o <file> 下载到文件; 优先 curl 后 wget
http_get() {
    local url="$1" out=""
    if [ "${2:-}" = "-o" ]; then
        out="${3:-}"
    fi
    if have_curl; then
        if [ -n "$out" ]; then
            curl -fsSL --connect-timeout 15 -o "$out" "$url"
        else
            curl -fsSL --connect-timeout 15 "$url"
        fi
    elif have_wget; then
        if [ -n "$out" ]; then
            wget -q --timeout=15 -O "$out" "$url"
        else
            wget -q --timeout=15 -O - "$url"
        fi
    else
        return 1
    fi
}

# 从仓库地址提取 owner/repo, 用于构造下载地址
repo_path() {
    printf '%s' "$1" | sed -e 's#^git@[^:]*:##' -e 's#^https\?://[^/]*/##' -e 's#\.git$##'
}

bootstrap_project() {
    local dir="$1"

    # 已在项目目录内直接运行(本地 clone / 源码目录)
    if [ -f docker-compose.yml ] && [ -f .env.example ]; then
        PROJECT_DIR="$(pwd)"
        info "已在项目目录内运行: ${PROJECT_DIR}"
        return 0
    fi

    # 复用之前生成过的安装目录
    if [ -f "$dir/docker-compose.yml" ] && [ -f "$dir/.env" ]; then
        PROJECT_DIR="$dir"
        info "复用已下载的部署文件: ${PROJECT_DIR}"
        return 0
    fi

    # 由脚本直接生成部署文件, 预构建镜像部署无需下载任何文件/代码
    info "生成部署文件到 ${dir} ..."
    mkdir -p "$dir"

    cat > "$dir/docker-compose.yml" <<'COMPOSE_EOF'
# 由 install.sh 自动生成, 与仓库 docker-compose.yml 保持同步
services:
  mysql:
    image: ${MYSQL_IMAGE:-mysql:8.0}
    container_name: dance-game-mysql
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      MYSQL_ROOT_PASSWORD: ${MYSQL_PASSWORD:-password}
      MYSQL_DATABASE: game_db
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    volumes:
      # MySQL 数据目录外置,容器重建不丢数据
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p\"$$MYSQL_ROOT_PASSWORD\" --silent"]
      interval: 5s
      timeout: 5s
      retries: 30

  redis:
    image: ${REDIS_IMAGE:-redis:7-alpine}
    container_name: dance-game-redis
    restart: unless-stopped
    command: ["sh", "-c", 'if [ -n "$$REDIS_PASSWORD" ]; then exec redis-server --requirepass "$$REDIS_PASSWORD"; else exec redis-server; fi']
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD:-}
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", 'if [ -n "$$REDIS_PASSWORD" ]; then redis-cli -a "$$REDIS_PASSWORD" ping | grep PONG; else redis-cli ping | grep PONG; fi']
      interval: 5s
      timeout: 5s
      retries: 30

  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: ${APP_IMAGE:-dance-game-app:latest}
    container_name: dance-game-app
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      MYSQL_HOST: mysql
      MYSQL_PORT: 3306
      MYSQL_DATABASE: game_db
      MYSQL_USER: root
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:-password}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      REDIS_PASSWORD: ${REDIS_PASSWORD:-}
      LOGIN_USERNAME: ${LOGIN_USERNAME:-admin}
      LOGIN_PASSWORD: ${LOGIN_PASSWORD:-123456}
      # 服务端口:默认 80,修改后同时生效于容器内监听端口与宿主机映射
      SERVER_PORT: ${SERVER_PORT:-80}
      # JWT 密钥:留空则首次运行自动生成并持久化到宿主机 ./data/jwt(挂载在 /data/jwt),重启复用
      JWT_SECRET_KEY: ${JWT_SECRET_KEY:-}
      JWT_SECRET_FILE: ${JWT_SECRET_FILE:-/data/jwt/dance-game-jwt-secret.key}
      # 上传文件路径(统一放在 /data/upload,容器重建不丢)
      FILE_UPLOAD_PATH: /data/upload
    volumes:
      # 应用数据统一挂载宿主机 ./data,容器内按子目录划分:
      # /data/jwt(JWT 密钥,避免旧 token 失效)、/data/upload(上传文件)
      - ./data:/data
    ports:
      - "${SERVER_PORT:-80}:${SERVER_PORT:-80}"

volumes:
  mysql-data:
  redis-data:
COMPOSE_EOF

    cat > "$dir/.env" <<'ENV_EOF'
# 由 install.sh 自动生成, 按需修改

# MySQL root 密码(默认 password)
MYSQL_PASSWORD=password

# Redis 密码(默认无密码,留空即可)
REDIS_PASSWORD=

# 系统登录账号
LOGIN_USERNAME=admin
LOGIN_PASSWORD=123456

# 服务端口(容器内监听与宿主机映射一致,默认 80)
SERVER_PORT=80

# JWT 签名密钥:留空则首次运行自动生成随机密钥并持久化到宿主机目录(Docker 下为 ./data/jwt)
# 显式设置后优先级最高,适用于多实例共享同一密钥等场景
JWT_SECRET_KEY=

# 自动生成密钥的持久化文件路径(宿主机目录挂载点,默认 Docker 内 /data/jwt)
JWT_SECRET_FILE=/data/jwt/dance-game-jwt-secret.key
ENV_EOF

    if [ ! -f "$dir/docker-compose.yml" ] || [ ! -f "$dir/.env" ]; then
        rm -rf "$dir" 2>/dev/null || true
        die "部署文件生成失败, 请检查 ${dir} 目录权限"
    fi
    PROJECT_DIR="$dir"
    info "部署文件生成完成"
}

# 本地构建镜像前确保有完整源码(git clone -> tarball)
ensure_source() {
    local dir="$PROJECT_DIR"
    if [ -f "$dir/Dockerfile" ] && [ -f "$dir/pom.xml" ]; then
        return 0
    fi

    info "本地构建需要完整源码, 开始下载项目代码到 ${dir} ..."
    if ! command -v git >/dev/null 2>&1 && ! have_curl && ! have_wget; then
        die "需要 git / curl / wget 下载源码, 请先安装"
    fi
    rm -rf "$dir" 2>/dev/null || true

    # 方式 A: git clone
    if command -v git >/dev/null 2>&1; then
        info "通过 git 下载项目: ${REPO_URL} (分支 ${REPO_BRANCH})"
        if git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$dir" >/dev/null 2>&1; then
            info "源码下载完成"
            return 0
        fi
        warn "git clone 失败, 尝试 tarball 下载..."
        rm -rf "$dir" 2>/dev/null || true
    fi

    # 方式 B: tarball 下载并解压
    local path tgz parent tmp extracted="" e
    path="$(repo_path "$REPO_URL")"
    if printf '%s' "$REPO_URL" | grep -q "gitee"; then
        tgz="${REPO_TARBALL_URL:-https://gitee.com/${path}/repository/archive/${REPO_BRANCH}.tar.gz}"
    else
        tgz="${REPO_TARBALL_URL:-https://codeload.github.com/${path}/tar.gz/refs/heads/${REPO_BRANCH}}"
    fi
    parent="$(dirname "$dir")"
    mkdir -p "$parent"
    tmp="$(mktemp -d)" || die "无法创建临时目录"
    info "通过 tarball 下载项目: ${tgz}"
    if (cd "$tmp" && http_get "$tgz" | tar -xz) >/dev/null 2>&1; then
        for e in "$tmp"/*/; do
            [ -d "$e" ] || continue
            extracted="$e"
            break
        done
    fi
    if [ -n "$extracted" ]; then
        mv "$extracted" "$dir" || die "解压项目失败"
        rm -rf "$tmp"
        info "源码下载完成"
        return 0
    fi
    rm -rf "$tmp" 2>/dev/null || true
    die "源码下载失败, 请手动执行: git clone ${REPO_URL} ${dir}"
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
    if ! have_curl && ! have_wget; then
        die "需要 curl 或 wget 才能安装 Docker, 请先安装"
    fi
    TMP_SCRIPT="$(mktemp)" || die "无法创建临时文件"
    local mirror="${DOCKER_INSTALL_MIRROR:-Aliyun}"

    # 1) 官方一键脚本 get.docker.com, 优先阿里云镜像源(国内成功率最高)
    info "下载 Docker 官方安装脚本(get.docker.com)..."
    if http_get "https://get.docker.com" -o "$TMP_SCRIPT"; then
        info "执行 Docker 安装(--mirror ${mirror})..."
        if $SUDO_ROOT sh "$TMP_SCRIPT" --mirror "$mirror"; then
            post_docker_install
            return 0
        fi
        warn "阿里云镜像源安装失败, 尝试默认源..."
        if $SUDO_ROOT sh "$TMP_SCRIPT"; then
            post_docker_install
            return 0
        fi
        warn "官方脚本安装失败..."
    else
        warn "get.docker.com 下载失败..."
    fi

    # 2) DaoCloud 镜像脚本兜底(国内可达性更好)
    info "改用 DaoCloud 安装脚本(get.daocloud.io)..."
    if http_get "https://get.daocloud.io/docker" -o "$TMP_SCRIPT" \
        && $SUDO_ROOT sh "$TMP_SCRIPT"; then
        post_docker_install
        return 0
    fi

    die "Docker 安装失败, 请手动安装: https://docs.docker.com/engine/install/"
}

# 安装成功后的收尾: 非 root 用户加入 docker 组
post_docker_install() {
    if [ "$(id -u)" -ne 0 ]; then
        $SUDO_ROOT usermod -aG docker "$(id -un)" 2>/dev/null || true
        warn "已将当前用户加入 docker 组(重新登录后可不加 sudo 使用 docker)"
    fi
    info "Docker 安装完成"
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

    # 兜底: 下载官方 docker compose 二进制(cli 插件), GitHub 失败回退 DaoCloud 镜像
    if have_curl || have_wget; then
        local url="https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)"
        local url_cn="https://get.daocloud.io/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)"
        local tmp
        tmp="$(mktemp)"
        info "从 GitHub 下载 docker compose 插件: ${url}"
        if ! http_get "$url" -o "$tmp"; then
            warn "GitHub 下载失败, 尝试 DaoCloud 镜像..."
            if ! http_get "$url_cn" -o "$tmp"; then
                rm -f "$tmp"
                warn "docker compose 二进制下载失败"
                return 1
            fi
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
    eval "new=\${$key:-}"
    if [ -n "$new" ]; then
        if password_ok "$new"; then
            set_env "$key" "$new"
            info "已使用环境变量更新 ${key}"
            return 0
        fi
        die "环境变量 ${key} 不符合强度要求(至少 8 位, 且同时包含数字和字母)"
    fi

    # 既没有终端输入, 也没有控制终端时, 视为非交互环境
    if [ ! -t 0 ] && [ ! -e /dev/tty ]; then
        die "当前为非交互环境, 请通过环境变量 ${key} 提供至少 8 位、含数字和字母的密码"
    fi

    while :; do
        printf '请输入新的%s (至少 8 位, 需包含数字和字母, 输入不回显): ' "$desc"
        # 兼容两种场景:
        #   - ./install.sh 直接运行: 标准输入即终端
        #   - curl ... | sh 管道运行: 标准输入已被脚本占用, 改用 /dev/tty 读取
        # 隐藏回显用 stty(避免依赖 read -s, dash 不支持)
        if [ -e /dev/tty ]; then
            stty -echo < /dev/tty 2>/dev/null || true
            if ! read -r new < /dev/tty; then
                stty echo < /dev/tty 2>/dev/null || true
                printf '\n'
                die "未输入密码, 已中止"
            fi
            stty echo < /dev/tty 2>/dev/null || true
        else
            if ! read -r new; then
                printf '\n'
                die "未输入密码, 已中止"
            fi
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
    local user pass
    user="$(get_env LOGIN_USERNAME admin)"
    pass="$(get_env LOGIN_PASSWORD 123456)"
    info ""
    info "================================================================"
    info "部署成功!"
    info "  访问地址  : http://localhost:${SERVER_PORT}"
    info "  登录账号  : ${user}"
    info "  登录密码  : ${pass}"
    info ""
    info "  查看日志  : $SUDO $COMPOSE_CMD logs -f app"
    info "  停止服务  : $SUDO $COMPOSE_CMD down"
    info "================================================================"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
    bootstrap_project "$INSTALL_DIR"
    cd "$PROJECT_DIR" || die "无法进入项目目录: ${PROJECT_DIR}"
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

    # 方式 3: 本地构建镜像(此时才需要完整源码)
    ensure_source
    if build_and_deploy; then
        print_success
        exit 0
    fi

    die "三种部署方式均失败, 请检查网络与日志后重试: $SUDO $COMPOSE_CMD logs -f app"
}

main
