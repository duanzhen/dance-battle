#!/usr/bin/env bash
#
# 启动 native 可执行文件并在其上跑 HTTP 端到端测试(native-e2e.py)。
#
# 用法:
#   scripts/native-e2e.sh [可执行文件路径]     # 默认 target/game
#
# 环境变量:
#   E2E_PORT      监听端口,默认 18080
#   E2E_WORKDIR   运行期数据目录(SQLite/JWT/上传),默认 mktemp -d
#
# 刻意不设置 DEPLOY_MODE / DB_TYPE / REDIS_ENABLED:验证 native 产物「零配置」
# 默认走 standalone(SQLite + 本地 SSE,不连 MySQL/Redis)。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY="${1:-$ROOT/target/game}"
PORT="${E2E_PORT:-18080}"
WORKDIR="${E2E_WORKDIR:-$(mktemp -d)}"

if [[ ! -x "$BINARY" ]]; then
  echo "native 可执行文件不存在或不可执行: $BINARY" >&2
  echo "先构建: mvn -Pnative -DskipFrontendBuild=true package" >&2
  exit 2
fi

mkdir -p "$WORKDIR"

SERVER_PORT="$PORT" \
SQLITE_FALLBACK_URL="jdbc:sqlite:$WORKDIR/game.db" \
JWT_SECRET_FILE="$WORKDIR/jwt.key" \
FILE_UPLOAD_PATH="$WORKDIR/upload" \
"$BINARY" >"$WORKDIR/server.log" 2>&1 &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "native PID=$SERVER_PID  数据目录=$WORKDIR  日志=$WORKDIR/server.log"

# 零配置形态自检:真正的「零配置」应当自报 standalone + SQLite(不探测 MySQL/Redis)
for _ in $(seq 1 30); do
  grep -q '运行配置' "$WORKDIR/server.log" && break
  sleep 1
done
if ! grep -q '运行配置' "$WORKDIR/server.log"; then
  echo "启动失败:未打印运行配置,日志尾部:" >&2
  tail -n 40 "$WORKDIR/server.log" >&2
  exit 1
fi
grep -q 'DEPLOY_MODE=standalone' "$WORKDIR/server.log" \
  || { echo "启动形态不符:期望 DEPLOY_MODE=standalone" >&2; exit 1; }
grep -q '数据库=SQLite' "$WORKDIR/server.log" \
  || { echo "启动形态不符:期望数据库=SQLite" >&2; exit 1; }

if ! E2E_BASE_URL="http://127.0.0.1:$PORT" python3 "$ROOT/scripts/native-e2e.py"; then
  echo
  echo "---- native 进程日志尾部 ----"
  tail -n 40 "$WORKDIR/server.log"
  exit 1
fi
