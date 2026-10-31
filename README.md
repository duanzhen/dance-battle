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

## MCP 接入

前后端同域部署时，MCP 入口就是当前域名下的:

```text
https://<当前域名>/mcp
```

支持 Streamable HTTP。客户端首次访问会收到带
`resource_metadata` 的 `401 WWW-Authenticate`，随后自动发现:

- `GET /.well-known/oauth-protected-resource/mcp`
- `GET /.well-known/oauth-authorization-server`
- `POST /register`：Dynamic Client Registration
- `GET /authorize`：跳转前端管理员授权页，使用 PKCE S256
- `POST /token`：authorization_code 换 Bearer JWT

这是用于 MCP 客户端接入的简化 OAuth 实现，不包含 consent、client secret、
refresh token、scope 管理或撤销列表。`client_id` 和 authorization code 本身都是
短期签名 JWT，注册信息、redirect URI、PKCE challenge 和 resource 直接编码在 token
中，服务端不保存注册表或授权码表。当前授权码在过期前可重复换取 token，接入生产环境
前应改为一次性存储或其他防重放机制。

浏览器 OAuth 请求会进入 `/mcp/authorize`：已有管理端登录态时直接签发授权码并返回
MCP 客户端；没有登录时先跳转 `/login`，登录成功后自动回到授权页继续。

默认 issuer/resource 按当前请求的 Host 推断；反向代理无法正确传递 Host 时可显式设置
`MCP_PUBLIC_BASE_URL=https://<当前域名>`。

MCP 业务工具按领域分组，写操作通过 `action` 分派并直接复用 Controller 背后的 Service，
因此事务、状态机、资源归属和赛制校验与网页端保持一致：

| 工具 | 覆盖的业务 |
| --- | --- |
| `list_tournaments` | 轻量赛事列表 |
| `manage_tournaments` | 赛事增删改查、模板建赛、导播凭证读取与轮换 |
| `manage_stages` | 赛段配置与链、初始化、生成对阵、开赛/完成/重置、擂台轮转、种子与晋级操作 |
| `manage_rosters` | 名单来源组、候选与预览、排位、加人/移出、人工覆盖、确认与跳过 |
| `manage_players` | 独立的自然人 Player 档案与 `players[]` 结构化导入 |
| `manage_competitors` | Competitor 参赛领域、签到/改签/解签、成员关系和场次参赛明细 |
| `manage_referees` | 裁判档案与凭证、赛段/场次分配、裁判工作台、打分提交 |
| `manage_matches` | 场次/轮次/原始分数、开赛与结果发布、自由对抗和当前上场选手 |
| `manage_displays` | 场景、控件、屏幕、图层与计时/视频状态、场景投射 |

写工具要求请求携带 OAuth 签发的 MCP access token，并在工具执行层再次校验
`sub=mcp-agent` 和 `scope` 中包含 `mcp`。SSE 长连接仍属于实时传输，不作为 MCP 工具；
但通过 `manage_displays` 投射场景会同时持久化屏幕状态并广播给已连接的大屏。

Agent 在开始复杂的多步任务前，可先通过 `prompts/list` 找到业务理解模板
`dance_battle_operator`，再调用 `prompts/get`：

```json
{
  "name": "dance_battle_operator",
  "arguments": {
    "goal": "检查淘汰赛名单为什么还不能开始",
    "focus": "roster",
    "tournamentId": "123",
    "stageId": "456"
  }
}
```

`goal` 必填；`focus` 可选 `general`、`setup`、`roster`、`competition`、`judging`、
`display` 或 `troubleshoot`；`tournamentId`、`stageId`、`matchId` 为可选的已知上下文。
模板会注入领域模型、状态机、工具映射、推荐执行顺序和高影响写操作约束。Prompt 本身
只生成上下文，不直接修改业务数据。

MCP Resource 用于放置只读上下文，不计入 Tool 数量。当前提供：

| Resource URI | 内容 |
| --- | --- |
| `dance://guide/business` | 建赛模式、操作纪律、工具选择和写入前检查 |
| `dance://guide/stage-modes` | 五种赛制的差异与结算要点 |
| `dance://guide/roster-dag` | 赛段链、名单有向无环图、中间态和物化规则 |
| `dance://tournaments` | 实时赛事索引 |
| `dance://tournaments/{tournamentId}/overview` | 赛事、赛段链、名单就绪度、场次状态和当前比赛摘要 |
| `dance://stages/{stageId}/overview` | 赛段配置、来源组、参赛方、裁判和场次摘要 |

Resource 全部为只读且继续受 MCP Bearer 认证保护。动态资源只返回操作摘要，
不会暴露导播/裁判 `authKey`，也不返回完整敏感明细。

创建类工具会返回 `context`，其中包含 `activeTournamentId`、`activeStageId` 和
`activeMatchId`。Agent 在当前对话中延续这些 ID：新建赛事后，后续未明确切换对象的操作默认
作用于刚创建的赛事；创建 Stage 后，后续名单、裁判和比赛操作默认作用于该 Stage。
只有用户明确切换对象或成功创建了新对象时才更新上下文，`clarification_required` 和失败结果
不会推进上下文。

调用统一使用 `action`，主键、分页和领域对象分别是独立参数。例如：

```json
{
  "name": "manage_stages",
  "arguments": {
    "action": "complete",
    "stageId": 123456789
  }
}
```

每个 `action` 的枚举、参数和领域对象字段可从 `tools/list` 的 input schema 获取。
返回同时包含可读文本和 `structuredContent`，业务异常通过 `isError=true` 返回。

批量导入选手使用独立的 `players` 数组：

```json
{
  "name": "manage_players",
  "arguments": {
    "action": "import",
    "tournamentId": 123456789,
    "players": [
      {"name": "林晓"},
      {"name": "陈宇", "tags": ["种子"]}
    ]
  }
}
```

该操作只创建 Player 档案，不会自动签到生成 Competitor。签到入口属于 Competitor 领域，
动作名是 `manage_competitors.register_player`；
取消参赛使用 `cancel_player_entry`，只解除参赛关系，不删除 Player。

多人签到时使用 `register_players`，一次提交 `entries[]`：

```json
{
  "action": "register_players",
  "entries": [
    {"playerId": "211...", "checkInType": "CREATE", "competitorNumber": "11", "zoneIndex": 1},
    {"playerId": "212...", "checkInType": "CREATE", "competitorNumber": "12", "zoneIndex": 1}
  ]
}
```

`checkInType=CREATE` 必须提供号码，`JOIN` 必须提供 `competitorId`。批量结果包含
`requested`、`succeeded`、`failed`、`registered` 和逐项 `errors`。
海选创建后会自动建立默认单圈，可以直接签到；单圈可省略 `zoneIndex`，
多圈时每项必须指定 `matchId` 或 `zoneIndex`。

涉及创建、更新、删除、重置、名单确认或结果发布时，Agent 必须先确认请求是否包含完整业务意图。
工具不会猜测用户没有明确表达的名称、赛制或模板。例如赛事创建缺少信息时，
`manage_tournaments` 返回：

```json
{
  "status": "clarification_required",
  "missing": ["data.name", "data.templateCode", "confirmed"],
  "questions": [
    "请确认新赛事的名称。",
    "请选择赛事模式：AUDITION_32、AUDITION_16 或 AUDITION_ARENA。"
  ]
}
```

此时 Agent 应先向用户确认，不能使用临时名称、复制现有赛事或根据相似赛事推断模式。
用户明确确认后，创建请求才传 `confirmed: true`。

赛事创建有两种明确模式：

- `create_from_template`：创建赛事，并按 `AUDITION_32`、`AUDITION_16` 或
  `AUDITION_ARENA` 生成完整赛段链。
- `create_blank`：只创建空白赛事草稿，不生成任何 Stage。之后用 `manage_stages`
  的 `create` 动作逐个创建赛段。

空白赛事创建阶段时，用户需要明确每个 Stage 的名称、`stageMode`、`teamCountStart`、
`teamCountEnd`，以及它在现有赛段链中的 `afterStageId`。除链头赛段外，缺少
`afterStageId` 会返回 `clarification_required`；Stage 创建还需要 `confirmed: true`。
Agent 不得根据模板或现有赛事自动补全这些信息。

`manage_stages.create` 支持直接传这些顶层字段，不需要包装到 `data`：

```json
{
  "action": "create",
  "tournamentId": "123",
  "name": "海选",
  "stageMode": "AUDITION",
  "teamCountStart": 32,
  "teamCountEnd": 16,
  "confirmed": true
}
```

`AUDITION` 未提供 `ruleConfig.circles` 或配置为 `0` 时默认使用 1 圈，并自动创建圈场次。
只有多圈时才需要明确圈数、每圈裁判与签到落圈方式。

圈管理可使用 `manage_stages`：

- `list_circles`：查看计划圈数、已创建圈场次及其状态。
- `add_circle`：追加一圈；必须提供新增圈的 `circleAdvanceCount`，也可提供
  `refereeIds` 或完整 `circleAdvanceCounts`。缺少晋级名额时返回 `clarification_required`。

新增 MCP 工具时，在 `com.dance.street.game.mcp.tools` 包内实现
`com.dance.street.game.mcp.IMcpTool` 并注册为 Spring Bean，即可自动进入
`tools/list`，无需修改服务器配置:

```java
@Service
public class MyMcpTool implements IMcpTool {
    public McpSchema.Tool definition() { /* name + inputSchema */ }
    public McpSchema.CallToolResult execute(McpTransportContext ctx, McpSchema.CallToolRequest req) {
        /* 业务逻辑；写操作需从 ctx 读取 claims 并校验权限 */
    }
}
```

工具名必须全局唯一，重复名称会在应用启动时直接失败。

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
