# 无败 · 街舞赛事管理系统

<p align="center">
  <img src="./frontend/src/assets/logo/logo.png" alt="无败 Logo" width="220" />
</p>

<p align="center">
  <a href="https://dancebattle.win">访问官网</a> ｜ <a href="https://docs.dancebattle.win/">查看文档</a>
</p>

一套面向街舞掰头赛事的办赛系统:从建赛、报名签到,到赛段编排、判罚打分、大屏投射,
一台电脑或一个容器就能跑完一整场比赛。后端 Spring Boot,前端 Vue 3,打包成一个文件部署。

## 能做什么

**办赛**

- 一键建赛:海选 + 淘汰链 + 场景 + 对战树一次配好,内置 32 人 / 16 人 / 擂台三种模板
- 手动编排:赛段可自由增删、改名、调规则,支持 **海选 / 淘汰 / 排名 / 擂台 / 自由对抗** 五种赛制
- 赛段链:上一段打完自动把晋级者送进下一段,衔接规则(取谁、取第几名、取多少人)可配

**名单**

- 名单中间态:两段之间的人先"过一遍"再确认 —— 拖拽换位、加外卡、剔除、从其他赛段拉人,改错了随时还原
- 多入口汇合:海选直入、复活赛等多条来路先进"待落位区",由导播摆到具体座位
- 复活赛、多圈海选、同分加赛(二海 / 三海)按名次段与名额自动取人

**比赛**

- 签到:选手建档 → 现场签到 → 按号落圈;支持迟到补签、改号改名、解除签到重排
- 淘汰赛:自动生成对阵、轮空自动结算、平局自动加赛一轮、冠军与季军赛自动接管
- 擂台赛:擂主守擂、挑战者轮转、临时或永久弃权
- 排名赛:多裁判按自定义维度打分,可选实时公布 / 导播确认后公布 / 全部完成后一次性公布
- 自由对抗:线下抽签定对手,系统只记录对战与晋级

**现场**

- 裁判端:按圈或按场次判罚,独立临时凭证,只看到自己该看的内容
- 手机导播台:开赛、开始场次、完成赛段、确认公布、擂台轮转、自由对抗加场
- 大屏投射:场景与控件可视化编辑(拖拽、图层),实时推送,多台机器可互相联动
- 选手抠图:浏览器内 AI 自动抠像,现场直接出透明底头像

**数据**

- Excel 导入导出名单与成绩;MySQL / SQLite 双数据库可选
- 管理端单账号登录,裁判与导播各用赛事专属凭证,互不影响

## 四个入口

| 入口 | 谁在用 | 做什么 |
| --- | --- | --- |
| 管理端 | 主办方 | 建赛、编排赛段、签到、确认名单、选手与裁判管理 |
| 手机导播台 | MC | 控场:开赛、开始场次、完成赛段、公布结果 |
| 裁判端 | 裁判 | 打分、判胜负平 |
| 大屏 | 观众 | 实时对战树、记分板、倒计时,无需登录 |

## 快速开始

### 一键部署(推荐,现场单机)

```bash
curl -fsSL https://dance-battel.oss-cn-hangzhou.aliyuncs.com/install.sh | sh
```

自动装好 Docker、拉取镜像并启动服务(默认装到 `~/dance-battle`)。完成后浏览器打开 <http://localhost>,用默认账号 `admin / 123456` 登录。

### Docker Compose(正式多实例)

```bash
cp docker/.env.example docker/.env      # 按需修改密码
docker compose -f docker/docker-compose.yml up -d --build
```

访问 <http://localhost>(端口可用 `SERVER_PORT` 修改)。数据库与缓存只在内部网络互通,不对宿主机开放端口。

### Docker 单机(不需要 MySQL / Redis)

```bash
docker compose -f docker/docker-compose.standalone.yml up -d --build
```

### 直接运行 Jar

```bash
./mvnw -Dmaven.test.skip=true package      # 打包(前端会自动构建并打进 Jar)
java -jar target/game-0.0.1-SNAPSHOT.jar   # 打开 http://localhost:8080
```

### 部署形态怎么选

不配置也能跑:连不上 MySQL 就自动退回 SQLite,连不上 Redis 就自动切本地推送。要确切控制,用下面三个值:

| 场景 | 配置 | 说明 |
| --- | --- | --- |
| 现场单机 | `DEPLOY_MODE=standalone` | SQLite + 本地推送,完全不连外部依赖,断网可用 |
| 本地试用 | 不配置 | 默认 `auto`:探测失败自动降级,怎么都能起来 |
| 正式多实例 | `DEPLOY_MODE=distributed` | 强制 MySQL + Redis,连不上直接报错,不静默降级 |

也可以只调其中一项:`DB_TYPE=sqlite` 单独指定数据库,`REDIS_ENABLED=false` 关掉 Redis(大屏改为单实例内联动)。

另有 GraalVM native 单文件版本(CI 产出),下载即可运行,零配置就是单机形态,适合没有 Docker 的现场。

数据(数据库文件、上传素材、登录密钥)统一放在 `/data` 下,用命名卷挂载即可持久化:

```bash
docker run -d -p 80:80 -v app-data:/data dance-game-app:latest
```

## 四个核心概念

不了解内部结构也能用,但知道这几个词,和现场沟通会顺很多。

| 概念 | 一句话 |
| --- | --- |
| 赛段链 | 一场比赛由若干赛段按顺序排列,决定展示顺序与新赛段的默认来路 |
| 来源组(出口) | 赛段之间的"取人规则"(图上的边):取谁、取第几名、取哪个圈、取多少人 |
| 中间态名单 | 两段之间的临时名单,一行一个座位;只有一条来路时自动落座,多条来路(汇合、多圈)先进待落座区由导播摆位 |
| 确认名单 | 把中间态落成下一段的正式参赛名单,之后这一段就能开赛 |

## 环境变量

以下变量都可以在启动前设置(Docker Compose 或 `java -jar` 同理),常用的只有前几个。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEPLOY_MODE` | Jar `auto` / native `standalone` | 部署形态:`auto` 探测失败自动降级;`standalone` 强制单机;`distributed` 强制 MySQL + Redis |
| `DB_TYPE` | `auto` | 数据库:`sqlite` 直接用本地文件库;`mysql` 强制 MySQL 且不回退 |
| `DB_URL` | 空 | 完整数据源地址;设成 `jdbc:sqlite:...` 即改用 SQLite |
| `DB_USERNAME` / `DB_PASSWORD` | 空 | 数据库账号密码(覆盖下面的 `MYSQL_*`) |
| `SQLITE_FALLBACK_URL` | `jdbc:sqlite:./data/game.db` | SQLite 文件位置(Docker 镜像内为 `/data/db/game.db`) |
| `DB_FALLBACK_SQLITE` | `true` | `auto` 模式下是否允许自动退回 SQLite |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | `mysql` / `3306` / `game_db` | MySQL 连接 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `root` / `password` | MySQL 账号 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `redis` / `6379` / 空 | Redis 连接 |
| `REDIS_ENABLED` | `true` | 设为 `false` 即单机模式:大屏改为本进程内推送 |
| `LOGIN_USERNAME` / `LOGIN_PASSWORD` | `admin` / `123456` | 管理端账号(首次启动时写入数据库) |
| `SERVER_PORT` | `80`(Docker)/ `8080`(本地 Jar) | 服务端口 |
| `JWT_SECRET_KEY` | 空(自动生成) | 登录密钥;留空则首次启动随机生成并保存到文件,重启不失效 |
| `JWT_SECRET_FILE` | `./data/jwt/dance-game-jwt-secret.key` | 密钥保存位置 |
| `FILE_UPLOAD_PATH` | `./upload`(Docker 内 `/data/upload`) | 上传文件目录 |
| `SCHEMA_INIT_ENABLED` | `true` | 启动时自动补齐缺失的数据表(只补缺失项,不动已有数据) |

更细的参数说明与实现细节见[文档站](https://docs.dancebattle.win/)。

## 登录与改密

- **首次启动**:数据库里没有账号时,按 `LOGIN_USERNAME` / `LOGIN_PASSWORD` 初始化(默认 `admin / 123456`)。
- **修改密码**:登录后右上角用户菜单 → 修改密码,新密码至少 8 位且同时包含字母和数字,写入数据库后重启不丢。
- **强制改密**:如果没显式设置 `LOGIN_PASSWORD`,或设置的就是默认值 `123456`,登录后会自动弹窗要求改密。
- **重置密码**:删除数据表 `t_login_account` 中的记录,下次登录会重新按环境变量初始化。
- **裁判 / 导播**:不使用管理端账号,各自凭赛事生成的专属凭证进入对应页面,可随时重置。

## 开发

```bash
./mvnw test                                  # 后端测试(约 270 个用例,跑在 SQLite 上,无需 MySQL/Redis)
cd frontend && npm install && npm run dev    # 前端开发模式
```

- 打包时 Maven 会自动构建前端并打进 Jar,不需要单独发布前端。
- CI 在 PR 上先跑后端测试,通过后再构建两种 Docker 镜像(Jar 与 GraalVM native)。

## 目录结构

```text
dance-battle/
├── src/main/java
│   ├── com/dance/street/game       赛事业务(controller / service / mapper / domain / engine)
│   └── org/dromara/common          通用框架(核心工具 / mybatis / sse / 缓存等)
├── src/main/resources              配置与 Mapper XML
├── src/test                        后端测试(集成测试为主,跑在 SQLite 上)
├── frontend/                       Vue 3 前端源码
├── sql/game_db.sql                 MySQL 建表脚本(17 张业务表)
├── sql/game_db.sqlite.sql          SQLite 建表脚本
├── docker/                         容器编排(多实例 / 单机 + 环境变量样例)
├── Dockerfile                      应用镜像
├── install.sh                      一键部署脚本
└── .github/workflows/build.yml     CI:后端测试 + 构建并推送镜像
```

## License

[MIT](./LICENSE)
