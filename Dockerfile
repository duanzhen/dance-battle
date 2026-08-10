# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
# 前端构建由 mvn package 自动完成(pom 中 exec 插件执行 pnpm install + build:prod)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 安装 Node 22 + pnpm(供 maven exec 插件调用)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && curl -fsSL https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-x64.tar.xz | tar -xJ -C /opt \
    && ln -s /opt/node-v22.14.0-linux-x64/bin/node /usr/local/bin/node \
    && ln -s /opt/node-v22.14.0-linux-x64/bin/npm /usr/local/bin/npm \
    && npm install -g pnpm@11 \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src
COPY frontend ./frontend

RUN mvn -B -Dmaven.test.skip=true package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    MYSQL_HOST=mysql \
    MYSQL_PORT=3306 \
    MYSQL_DATABASE=game_db \
    MYSQL_USER=root \
    MYSQL_PASSWORD=password \
    REDIS_HOST=redis \
    REDIS_PORT=6379 \
    REDIS_PASSWORD= \
    LOGIN_USERNAME=admin \
    LOGIN_PASSWORD=123456

COPY --from=build /app/target/game-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
