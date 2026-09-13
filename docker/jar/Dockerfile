# syntax=docker/dockerfile:1
#
# jar 镜像(预构建产物版),CI 用。
#
# 与根目录 Dockerfile 的区别:这里**不跑 maven**。CI 已经用 GraalVM 编好了 jar
# (同一次 mvn 调用同时产出 jar 与 native 可执行文件),这里只把它装进 JRE 镜像,
# 避免「CI 编一次 + Docker 里再编一次」。
# 根目录那份是自包含的(容器内 mvn package),留给本地 docker compose up --build 与 install.sh。
#
# 构建上下文就是这个目录。本地构建:
#   mvn -DskipFrontendBuild=true package
#   cp target/game-0.0.1-SNAPSHOT.jar docker/jar/app.jar
#   docker build -t dance-game-app docker/jar
#
# 注意:下面的 ENV 与根 Dockerfile 的运行阶段必须保持一致(两处需同步修改)。
FROM eclipse-temurin:21-jre

WORKDIR /app

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

COPY app.jar app.jar

VOLUME ["/data"]

EXPOSE 80
ENTRYPOINT ["java", "-jar", "app.jar"]
