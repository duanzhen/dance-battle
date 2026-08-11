# 无败 - 街舞赛事管理系统

无败-面向街舞舞者的掰头平台 赛事创建、赛段编排、场次生成、打分结算、裁判端判罚、大屏实时投射（分布式 SSE）与手机导播台。后端基于 Spring Boot 4，前端为 Vue 3 前后端统一打包为单 Jar 部署。

## 技术栈

### 后端

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 4.0.7 | WebMVC + Validation + AOP |
| Java | 21 | |
| MyBatis-Plus | 3.5.16 | `mybatis-plus-spring-boot4-starter`，分页插件 |
| Sa-Token | 1.45.0 | 单账号登录，`@SaCheckPermission` 注解鉴权 |
| Redisson | 4.7.0 | Redis 客户端，支撑分布式 SSE 发布订阅与分布式锁 |
| FastExcel / Hutool / MapStruct-Plus | 1.3.0 / 5.8.40 / 1.5.0 | 导出、工具、VO/BO 转换 |

### 前端（`frontend/`）

| 组件 | 版本 |
| --- | --- |
| Vue | 3.5 |
| Vite | 6.4 |
| Element Plus | 2.11 |
| Pinia / Vue Router | 3 / 4.6 |
| UnoCSS | 66 |

## 功能

- 赛事管理：赛事大厅、按模板一键创建（海选 + 淘汰赛链 + 场景 + 对战树控件）
- 赛段编排：海选 / 擂台 / 淘汰 / 决赛等赛制，晋级规则可配置
- 场次与对战树：自动生成场次、种子配对、场次结算
- 打分引擎：总分制 / 胜负平制 多裁判聚合、排名计算
- 裁判端：独立临时鉴权，判罚页与大屏定向推送
- 大屏投射：基于SSE，多实例可互相广播
- MC： 手机导播台流程管理
- 登录：单账号（账号密码写在配置/环境变量，默认 `admin / 123456`），登录后直达赛事大厅

## 目录结构

```text
dance-battle/
├── src/main/java
│   ├── com/dance/street/game        应用入口/配置 + 赛事业务模块(controller/service/mapper/domain/engine)
│   └── org/dromara
│       └── common                   通用框架类(core/mybatis/sse/tenant 等)
├── src/main/resources               配置文件 + mapper XML
├── frontend/                        Vue3 前端源码
├── sql/game_db.sql                  数据库初始化脚本(13 张业务表)
├── Dockerfile / docker-compose.yml  容器化部署
├── .env.example                     环境变量示例
└── .github/workflows/build.yml      CI:构建 jar + 推送 Docker 镜像
```

## 快速开始

### 方式一：Docker Compose

```bash
cd dance-game
cp .env.example .env     # 按需修改密码
docker compose up -d --build
```

启动后访问 <http://localhost>（默认端口 80，可通过 `SERVER_PORT` 环境变量修改）。Compose 会：

- 启动 MySQL 8.0，数据目录外置到 `mysql-data` 卷，首次启动自动导入 `sql/game_db.sql` 建表
- 启动 Redis 7
- 构建并启动应用（等待 MySQL/Redis 健康检查通过后）

> MySQL/Redis 仅暴露在 Compose 内部网络，不映射宿主机端口，只能由应用容器通过 `mysql` / `redis` 服务名访问。
> JWT 密钥默认自动生成并持久化到宿主机 `./data/jwt`（容器内挂载于 `/var/tmp/jwt`），容器重建不更换密钥，旧登录态保持有效。

应用启动时还会用 JDBC 做一次 schema 自检：数据库或业务表缺失时自动读取 `sql/game_db.sql` 建库建表（只补缺失的表，不删不改已有数据）。因此即使绕过 MySQL 初始化脚本、直连已有 MySQL 实例，表结构也会自动就绪；可通过 `SCHEMA_INIT_ENABLED=false` 关闭。

### 方式二：打包 Jar

```bash
# 打包(自动构建前端并打进 static)
./mvnw -Dmaven.test.skip=true package

# 运行
java -jar target/game-0.0.1-SNAPSHOT.jar
```

访问 <http://localhost:8080>（本地 jar 默认端口 8080）。

## 环境变量

以下变量均可通过环境变量覆盖（Docker Compose 或 `java -jar` 前设置）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_HOST` | `mysql` | MySQL 地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `game_db` | 数据库名 |
| `MYSQL_USER` | `root` | 数据库用户 |
| `MYSQL_PASSWORD` | `password` | 数据库密码 |
| `REDIS_HOST` | `redis` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码（空 = 无密码） |
| `LOGIN_USERNAME` | `admin` | 系统登录账号 |
| `LOGIN_PASSWORD` | `123456` | 系统登录密码 |
| `SERVER_PORT` | `80`（Docker）/ `8080`（本地 jar） | 服务端口，修改后宿主机映射与容器内监听端口同步变更 |
| `JWT_SECRET_KEY` | 空（自动生成） | JWT 签名密钥；显式设置后优先级最高，留空则首次运行随机生成并持久化到密钥文件 |
| `JWT_SECRET_FILE` | `/var/tmp/jwt/dance-game-jwt-secret.key` | 自动生成的密钥持久化文件路径（Docker 下挂载宿主机 `./data/jwt`，本地 jar 默认 `/var/tmp/dance-game-jwt-secret.key`） |
| `FILE_UPLOAD_PATH` | `./upload`（Docker 内为 `/app/upload`） | 文件上传存储目录 |
| `SCHEMA_INIT_ENABLED` | `true` | 启动时自动检查/创建数据库与表结构（JDBC 兜底，读取 `sql/game_db.sql`） |
