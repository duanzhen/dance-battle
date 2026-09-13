# syntax=docker/dockerfile:1
#
# native 镜像(预构建产物版),CI 用。
#
# 构建上下文就是这个目录,因为根目录 .dockerignore 排除了 target/,
# 而 native 产物必须进上下文。本地构建:
#   mvn -Pnative -DskipFrontendBuild=true package   # 需要 GraalVM JDK
#   cp target/game docker/native/game
#   docker build -t dance-game-native docker/native
#
# 基础镜像用 ubuntu:24.04 而不是 debian:12-slim:产物在 ubuntu-latest 上编译,
# 按该机的 glibc 符号版本链接,跑在更老的 glibc 上会报 "version `GLIBC_x.y' not found"。
FROM ubuntu:24.04

# tzdata:产物按 TZ 取本地时区,缺它会静默退回 UTC;
# zlib1g / libstdc++6:GraalVM native 产物的动态依赖(zlib 已在本地产物上实测)。
# DEBIAN_FRONTEND=noninteractive:tzdata 的 debconf 在无 TTY 的构建里要非交互,否则可能卡住
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata zlib1g libstdc++6; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY game /app/game
RUN chmod +x /app/game

# 环境变量与根 Dockerfile(jar 镜像)对齐;DEPLOY_MODE 显式写 standalone:
# native 产物本就无法连 Redis(自动装配在 AOT 阶段已排除),写清楚避免误解。
ENV TZ=Asia/Shanghai \
    DEPLOY_MODE=standalone \
    LOGIN_USERNAME=admin \
    LOGIN_PASSWORD=123456 \
    SERVER_PORT=80 \
    SQLITE_FALLBACK_URL=jdbc:sqlite:/data/db/game.db \
    JWT_SECRET_FILE=/data/jwt/dance-game-jwt-secret.key \
    FILE_UPLOAD_PATH=/data/upload

# 数据统一落在 /data 子目录:/data/db(SQLite)、/data/jwt(密钥)、/data/upload(上传)
VOLUME ["/data"]

EXPOSE 80
ENTRYPOINT ["/app/game"]
