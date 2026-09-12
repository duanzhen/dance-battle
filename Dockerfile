# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
# 前端构建由 mvn package 自动完成(pom 中 exec 插件执行 npm install + build:prod)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 安装 Node 22 + npm(供 maven exec 插件调用)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && curl -fsSL https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-x64.tar.xz | tar -xJ -C /opt \
    && ln -s /opt/node-v22.14.0-linux-x64/bin/node /usr/local/bin/node \
    && ln -s /opt/node-v22.14.0-linux-x64/bin/npm /usr/local/bin/npm \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src
COPY frontend ./frontend
# 建表脚本(pom 会将其打进 jar 的 classpath:sql/game_db.sql, 供启动时 JDBC 兜底建表)
COPY sql ./sql

RUN mvn -B -Dmaven.test.skip=true package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 数据统一落在 /data 子目录(裸 docker run 时生效;compose 会再覆盖)
ENV TZ=Asia/Shanghai \
    DEPLOY_MODE=auto \
    DB_TYPE=auto \
    MYSQL_HOST=mysql \
    MYSQL_PORT=3306 \
    MYSQL_DATABASE=game_db \
    MYSQL_USER=root \
    MYSQL_PASSWORD=password \
    REDIS_HOST=redis \
    REDIS_PORT=6379 \
    REDIS_PASSWORD= \
    LOGIN_USERNAME=admin \
    LOGIN_PASSWORD=123456 \
    SERVER_PORT=80 \
    SQLITE_FALLBACK_URL=jdbc:sqlite:/data/db/game.db \
    JWT_SECRET_FILE=/data/jwt/dance-game-jwt-secret.key \
    FILE_UPLOAD_PATH=/data/upload

COPY --from=build /app/target/game-0.0.1-SNAPSHOT.jar app.jar

# ---------- 数据持久化 ----------
# VOLUME 声明挂载点:统一挂载 /data,内部按子目录划分:
#   /data/db      SQLite 数据库文件(game.db)
#   /data/jwt     JWT 密钥文件(dance-game-jwt-secret.key)
#   /data/upload  上传文件
# 容器重建后数据仍保留。子目录由应用启动时自动创建,无需单独挂载。
# 注意:Dockerfile 只能声明匿名卷;匿名卷在容器删除后仍留在磁盘上,但新建容器不会自动复用。
# 要可控的命名卷/宿主机目录持久化,请用:
#   docker run -v app-data:/data ...
# 或直接使用 docker-compose.standalone.yml(已配好命名卷)。
VOLUME ["/data"]

EXPOSE 80
ENTRYPOINT ["java", "-jar", "app.jar"]
