# 无败 - 街舞赛事管理系统

无败-面向街舞舞者的掰头平台 赛事创建、赛段编排、场次生成、打分结算、裁判端判罚、大屏实时投射（分布式 SSE）与手机导播台。后端基于 Spring Boot 4，前端为 Vue 3 前后端统一打包为单 Jar 部署。

## 功能

- 赛事管理：赛事大厅、按模板一键创建（海选 + 淘汰赛链 + 场景 + 对战树控件）
- 赛段编排：海选 / 擂台 / 淘汰 / 排名 / 决赛等赛制，晋级规则可配置；排名赛支持多裁判按自定义维度打分，并可配置实时/手动/全部完成后一次性公布
- 场次与对战树：自动生成场次、种子配对、场次结算
- 打分引擎：总分制 / 胜负平制 多裁判聚合、排名计算
- 裁判端：独立临时鉴权，判罚页与大屏定向推送
- 大屏投射：基于SSE，多实例可互相广播
- MC： 手机导播台流程管理
- 登录：单账号（账号密码写在配置/环境变量，默认 `admin / 123456`），登录后直达赛事大厅；登录后可在右上角用户菜单“修改密码”，新密码持久化到数据库，重启不丢失

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

- 启动 MySQL 8.0
- 启动 Redis 7
- 构建并启动应用（等待 MySQL/Redis 健康检查通过后）

> MySQL/Redis 仅暴露在 Compose 内部网络，不映射宿主机端口，只能由应用容器通过 `mysql` / `redis` 服务名访问。
> JWT 密钥默认自动生成并持久化到宿主机 `./data/jwt`（容器内挂载于 `/var/tmp/jwt`），容器重建不更换密钥，旧登录态保持有效。

应用启动时会用 JDBC 做一次 schema 自检：数据库或业务表缺失时自动读取建表脚本补齐（MySQL 用 `sql/game_db.sql`，SQLite 用 `sql/game_db.sqlite.sql`；只补缺失的表/索引，不删不改已有数据）。因此即使绕过初始化脚本、直连已有实例，表结构也会自动就绪；可通过 `SCHEMA_INIT_ENABLED=false` 关闭。

### 方式二：打包 Jar

```bash
# 打包(自动构建前端并打进 static)
./mvnw -Dmaven.test.skip=true package

# 运行
java -jar target/game-0.0.1-SNAPSHOT.jar
```

访问 <http://localhost:8080>（本地 jar 默认端口 8080）。

### 方式三：SQLite（免 MySQL，适合本地试用/演示）

无需 MySQL 时，设置 `DB_URL` 指向 SQLite 文件即可，启动时自动按 `sql/game_db.sqlite.sql` 建表建索引：

```bash
DB_URL=jdbc:sqlite:./data/game.db java -jar target/game-0.0.1-SNAPSHOT.jar
```

- 数据文件所在目录不存在时会自动创建；重复启动只补缺失项，不会破坏已有数据。
- SQLite 模式无需 `MYSQL_*` / `DB_USERNAME` / `DB_PASSWORD`，但 Redis 仍然必需。
- 也可以什么都不配置：MySQL 未配置或连接失败时，应用会自动回退到 SQLite（默认 `jdbc:sqlite:./data/game.db`，可用 `SQLITE_FALLBACK_URL` 修改；`DB_FALLBACK_SQLITE=false` 关闭回退）。
- SQLite 连接会自动追加 `date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS`（与建表脚本中的 TEXT 日期列一致），避免时间字段出现 `Error parsing time stamp`；手动设置 `date_class` 时以你设置的值优先。

> 如果你在旧版本下已经生成过 `data/game.db`（时间列被写成了毫秒数字串），修复参数只影响新写入。最简单的处理是删除 `data/game.db` 后重启让应用重建（演示数据会丢失），或按
> `UPDATE 表名 SET create_time = datetime(create_time / 1000, 'unixepoch', 'localtime') WHERE create_time GLOB '[0-9]*';`
> 逐表转换（`update_time` 同理）。

### 方式四：单机模式（免 Redis，无需多实例联动）

Redis 未配置或连接失败时，应用会自动进入单机模式，无需任何额外设置：

- SSE 改为进程内本地广播：大屏/裁判端/管理端推送在单实例内照常工作；
- 登录失败 IP 限流改为本地计数（`login.rate-limit.*` 配置仍然生效）；
- 控件图层排序的分布式锁退化为 JVM 本地锁（单实例内仍原子）；
- 不再创建 RedissonClient，不依赖 Redis 服务器；多实例联动能力相应关闭。

显式禁用 Redis 可设 `REDIS_ENABLED=false`（跳过连接探测）；需要多实例部署时保持 Redis 可用即可恢复原有联动。

### 单镜像运行与数据持久化

镜像内已通过 `VOLUME` 声明 `/app/data`（SQLite + JWT 密钥）和 `/app/upload`（上传文件）两个挂载点。直接裸跑会自动回退 SQLite + 单机模式：

```bash
docker build -t dance-game-app:latest .
docker run -d --name dance-game -p 80:80 dance-game-app:latest
```

> Dockerfile 的 `VOLUME` 是匿名卷：容器删除后数据仍留在磁盘，但重建容器不会自动复用。要可靠的持久化，用命名卷（推荐）或宿主机目录：

```bash
docker run -d --name dance-game -p 80:80 \
  -v app-data:/app/data -v app-upload:/app/upload \
  dance-game-app:latest
```

JWT 密钥默认写入 `/app/data/jwt`（与 SQLite 数据库同卷），一个 `app-data` 卷即可同时持久化数据库与登录密钥，无需单独挂载。

一条命令起单机并持久化（命名卷，容器删除重建不丢数据）：

```bash
docker compose -f docker-compose.standalone.yml up -d --build
```

## 环境变量

以下变量均可通过环境变量覆盖（Docker Compose 或 `java -jar` 前设置）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 空（使用 MySQL 连接） | 数据源完整 JDBC URL；设为 `jdbc:sqlite:...` 即切换为 SQLite（自动补齐时间参数） |
| `DB_USERNAME` | 空 | 数据库用户名（覆盖 `MYSQL_USER`） |
| `DB_PASSWORD` | 空 | 数据库密码（覆盖 `MYSQL_PASSWORD`） |
| `DB_FALLBACK_SQLITE` | `true` | MySQL 未配置/连接失败时自动回退 SQLite 文件库（`false` 关闭） |
| `SQLITE_FALLBACK_URL` | `jdbc:sqlite:./data/game.db` | 自动回退时使用的 SQLite 连接（自动补齐时间参数） |
| `MYSQL_HOST` | `mysql` | MySQL 地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `game_db` | 数据库名 |
| `MYSQL_USER` | `root` | 数据库用户 |
| `MYSQL_PASSWORD` | `password` | 数据库密码 |
| `REDIS_HOST` | `redis` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码（空 = 无密码） |
| `REDIS_ENABLED` | `true` | Redis 未配置/连接失败时自动进入单机模式（`false` 显式关闭 Redis） |
| `LOGIN_USERNAME` | `admin` | 系统登录账号 |
| `LOGIN_PASSWORD` | `123456` | 系统登录密码 |
| `SERVER_PORT` | `80`（Docker）/ `8080`（本地 jar） | 服务端口，修改后宿主机映射与容器内监听端口同步变更 |
| `JWT_SECRET_KEY` | 空（自动生成） | JWT 签名密钥；显式设置后优先级最高，留空则首次运行随机生成并持久化到密钥文件 |
| `JWT_SECRET_FILE` | `./data/jwt/dance-game-jwt-secret.key` | 自动生成的密钥持久化文件路径（Docker 单机下为 `/app/data/jwt/...`，与 SQLite 同卷；多实例 compose 下为 `/var/tmp/jwt/...`，挂载宿主机 `./data/jwt`） |
| `FILE_UPLOAD_PATH` | `./upload`（Docker 内为 `/app/upload`） | 文件上传存储目录 |
| `SCHEMA_INIT_ENABLED` | `true` | 启动时自动检查/创建数据库与表结构（JDBC 兜底，按数据源读取 `sql/game_db.sql` 或 `sql/game_db.sqlite.sql`） |

### 修改密码与重置

- 修改密码：登录后在右上角用户菜单选择“修改密码”，校验旧密码后写入数据库表 `t_login_account`（BCrypt 加密）。新密码需至少 8 位且同时包含字母和数字，修改后需使用新密码重新登录。
- 强制改密：未显式传入 `LOGIN_PASSWORD` 环境变量，或传入的值等于内置默认密码 `123456` 时，登录后前端会强制弹出改密窗口，此时无需输入旧密码；显式通过环境变量配置其他自定义密码时不算默认密码，不触发强制改密。
- 首次启动：数据库中没有账号记录时，以 `LOGIN_USERNAME` / `LOGIN_PASSWORD`（默认 `admin / 123456`）初始化，之后以数据库记录为准，环境变量不再覆盖已修改的密码。
- 重置密码：删除 `t_login_account` 表记录（或执行 `UPDATE t_login_account SET password = ...`），下次登录时重新按环境变量初始化。
