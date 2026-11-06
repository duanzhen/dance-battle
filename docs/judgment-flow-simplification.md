# 判罚与比赛流程引擎：简化方案

状态：提案，未改引擎。读者是负责这块的开发 / 负责人，用来决定要不要按文末阶段做。

结论先说：产品需要的五种赛制、判罚和晋级都是真的，不该砍。过度的是同一件事被做成了四五套「可扩展点」，而真正的状态推进仍然写在 `TMatchResultServiceImpl`（约 1500 行）的 if 链里。建议收成「一条显式状态流 + 每种赛制一个类」，分三期做，每期都能单独上线。

假设核对：成立。`StageHandler` / `StageSettler` / `StageGenerator` / `StageModeProfiles` / `StageHooks` 是叠在一起的分派层，不是五套不同的产品能力。没有 Visitor。所谓插件就是同一套 Spring `List` 注入重复了四次。

本文不改名单边（roster）、大屏可视化、HTTP 契约和表结构。那些是相邻系统，见文末「不做的事」。

---

## 1. 现状地图

### 1.1 业务对象（这些要留）

一场街舞 1v1 赛事在数据上是四层，状态都是字符串，定义在 `engine/common/StageConstants.java`。

| 层 | 表 / 类 | 状态 |
| --- | --- | --- |
| 赛事 | `t_tournament` / `TTournament` | 不参与判罚机 |
| 赛段 | `t_stage` / `TStage.stageMode` + `status` | `DRAFT → GAMING → SETTLED`，另有 `DISCARD` |
| 场次 | `t_match` / `TMatch` | `PENDING → GAMING → SETTLED` |
| 局 | `t_match_round` | 与场次同一套三个字符串 |
| 判罚明细 | `t_round_score` | `action=SCORE` 打分，`action=VOTE` 胜负票 |
| 场次内名次 | `t_match_participant.outcome_status` | `PENDING/WIN/LOSS/DRAW`（`MatchOutcomeEnum`） |
| 赛段内去留 | `t_competitor.outcome_status` | `PENDING/ADVANCE/ELIMINATED/WITHDRAWN`（`OutcomeStatusEnum`） |

赛制只有五种，`engine/common/enums/StageModeEnum.java`：

- `AUDITION` 海选：按圈打分，完成赛段时按分晋级，晋级线同分则生成二海 / 三海。
- `RANK` 排名赛：全员一场，多维度打分，完成赛段时排名；同分超名额则留给导播在中间态手调。
- `KNOCKOUT` 淘汰赛：一轮一个赛段，1v1，可 BO1/BO3/BO5，胜者经 `promotion_rule` 进下游座位或标晋级。
- `ARENA` 擂台赛：不预生成树，导播逐场开，胜者守擂。
- `FREE_MATCH` 自由对抗：导播手动加两人对决，再手动勾晋级者。

「怎么判」是另一个枚举 `MatchModeEnum`：`STANDARD` 判胜负平、`VOTING` 总分、`RANKING` 多维。它写在 `t_match.match_mode`。`ruleConfig.scoring.type`（`ScoreTypeEnum`：`WIN_LOSS_DRAW` / `TOTAL_SCORE` / `MULTI_DIM`）与它对齐，但运行时没有任何分支读这个枚举，见第 2 节。

赛段之间「谁晋级到哪」不在判罚机里。链的显示顺序是 `t_stage.next_stage_id`（`service/impl/StageChain.java`）。真正带人的是名单边 `t_stage_roster_group` / entry（`service/impl/roster/*`）。判罚机只负责把 `outcome_status` / `final_rank` 写好，并在写完后调用 `rosterService.syncPreAdvance` / `rebuildEntriesOfDownstream`。

### 1.2 入口

导播台和管理端各有一套几乎相同的生命周期 URL，都进同一个服务。

| 动作 | 导播 | 管理端 | 落到 |
| --- | --- | --- | --- |
| 初始化 / 生成对阵 / 开始赛段 / 完成 / 撤销 | `controller/DirectorController.java` | `controller/TStageController.java` | `ITStageLifecycleService` |
| 开始场次 / 取消开始 / 重置 / 提交结果 / 公布 | 同上 | `controller/TMatchController.java` | `ITMatchResultService` |
| 裁判看场、提交 | `controller/RefereeMatchController.java` | — | `RefereeMatchServiceImpl` → 再调 `ITMatchResultService.submitResult` |
| 擂台下一场、自由对抗加场、海选补签 | Director / Stage | 同左 | 生命周期门面再转到 `ArenaRunService` / `FreeMatchService` / `StageCheckInService` |

裁判提交不自己结算。`RefereeMatchServiceImpl` 组 `SubmitResultBo` 后调用 `TMatchResultServiceImpl.submitResult`。

事件：写路径上到处调用 `TournamentEventNotifier.notify(..., type)` 和 `RefereeSseNotifier`。`type` 是裸字符串：`stage` / `match` / `scores` / `reset`。事务提交后才推（`TournamentEventNotifier`）。没有领域事件总线，这是好事，简化时保持这些字符串不变。

### 1.3 一次典型 1v1（淘汰赛）实际走过的类

下面是「开始赛段 → 开始一场 → 裁判投票 → 胜者进下一场 → 完成赛段」。箭头是真实调用，不是文档里的理想分层。

```
PUT .../stage/{id}/start
  TStageLifecycleServiceImpl.startStage          // 只加 @Transactional
    StageCommands.startStage                     // 转一手
      StageRunService.startStage
        handler.capabilities() → StageModeProfiles.of   // 画像表
        若还没有场次:
          StageSetupService.initialize / generateMatches
            StageHooks.setupHook                  // 海选才有实现，淘汰赛空
            handler.generateBracket
              KnockoutStageHandler               // 6 行委托
                KnockoutGenerator.generate       // 这里才是种子位算法
            落库 t_match / round / participant，status=PENDING
        StageHooks.startGuard                    // 海选才有，淘汰赛空
        ensureStageGaming                        // DRAFT→GAMING
        singleActiveMatch=true → 场次保持 PENDING

POST .../match/{id}/start
  TMatchResultServiceImpl.startMatch             // 不经过 Handler
    真人 ≤1 → ByeSettlementService（轮空）
    淘汰赛：其他 GAMING 场次回 PENDING（已有票则拒绝）
    MatchStateWriter：本场 PENDING→GAMING

POST .../submit-result  （裁判端或导播台）
  TMatchResultServiceImpl.submitResult           // 真正的状态机在这里
    profile.perCompetitor ? 海选/排名只攒分
    mode 是 VOTING/RANKING ? TScoredMatchServiceImpl.accumulateScores（不结算）
    否则 settleMatchResult:
      写 VOTE 行 → aggregateRefereeVotes（人齐才往下）
      ScoringEngine → WinLossDrawStrategy        // 把已定的 WIN/LOSS 映射成分
      resolveKnockoutSeries（BO1/BO3/BO5、平局加局、提前打出胜负）
      publishMode=MANUAL → 结果写入 t_match.result_json，场次仍 GAMING
      否则 settleAndAdvance:
        ParticipantScoreWriter
        resolveKnockoutDownstream                // 与 TScoredMatchServiceImpl 里有一份拷贝
          DownstreamRouter（promotion_rule: "1" 胜者 / "2" 败者）
        rosterService.syncPreAdvance
        MatchStateWriter：场次 SETTLED
        TournamentEventNotifier "match"

PUT .../stage/{id}/complete
  StageCommands.completeStage
    StageSettlementService.completeStage
      handler.settle
        KnockoutStageHandler → ScoredStageSettler  // 再查一遍「场次是否都 SETTLED」
      stage GAMING→SETTLED（带 status 条件更新）
      rosterService.rebuildEntriesOfDownstream
```

海选 / 排名的分叉不在 Handler 里，而在 `submitResult` 开头三行 if：逐选手赛制只写 `t_round_score` 并保持 `GAMING`；`completeStage` 才进 `AuditionStageSettler`（约 930 行，含加赛）或 `RankStageSettler`（约 329 行）。

擂台开赛会走到 `ArenaStageHandler.afterStart` → `ArenaRunService.startNextArenaMatch`。这是五个 Handler 里唯一覆盖了生命周期钩子的地方。

### 1.4 现在的类型分层

从外到内，一次「完成赛段」要穿过这些类型。带 `*` 的是分派壳，几乎没有自己的规则。

```
Controller
  ITStageLifecycleService
    TStageLifecycleServiceImpl          门面，278 行，方法体全是委托
      StageCommands*                    63 行，再委托 + 调用空钩子
        StageSetupService / StageRunService / StageSettlementService
          StageHandlerRegistry*
            XxxStageHandler*            每个 30 行上下，settle/generate 各一行委托
              StageGenerator            纯计算（海选建圈、淘汰种子位、排名单场）
              StageSettler              真正写晋级的地方
          StageHooks*                   只有海选两个实现
          StageModeProfiles             五种赛制的布尔画像，Handler.capabilities() 原样转发
```

判罚提交不走上面这棵树，走：

```
RefereeMatchServiceImpl / TMatchController
  TMatchResultServiceImpl               1502 行
    ScoringEngine* → ScoreStrategy      三种算法，本身不长
    TScoredMatchServiceImpl             累计打分 + 另一份淘汰下游
    flow/*                              少量值得留的写入口
```

`engine/generator/StageGeneratorFactory` 仍在 Spring 里注册全部 `StageGenerator`（含一个永远返回空计划的 `FreeMatchGenerator`）。生产路径已经改走 `StageHandler.generateBracket`，工厂的 `generate()` 没有任何调用方，只被 `EngineCoverageTest` 用来断言「该有生成器的赛制都注册了」。

---

## 2. 过度设计点

下面每一条都是可以指到文件的。不是「抽象不好」，是这些抽象没有把复杂度从调用方拿走。

### 2.1 四套注册表描述同一种赛制

新增或改一种赛制，今天要同时碰：

| 注册点 | 文件 | 实际作用 |
| --- | --- | --- |
| 画像表 | `engine/common/StageModeProfiles.java` | 布尔开关：生不生成树、是否逐场、是否逐选手 |
| Handler | `service/impl/stage/handler/*StageHandler.java` | `capabilities()` 默认就是 `StageModeProfiles.of`；`settle` / `generateBracket` 一行转发 |
| Settler | `service/impl/settle/*StageSettler.java` | 结算算法。这是真逻辑 |
| Generator | `engine/generator/*Generator.java` + `StageGeneratorFactory` | 对阵纯计算。工厂已不在生产路径上 |
| Hooks | `service/impl/stage/hook/*` | 只有海选：生成前校验、开赛前「人人落圈且每圈有裁判」 |

`StageHandler` 自己的注释写着「不再经 SettlerRegistry / GeneratorFactory 分派」。工厂还在；Settler 接口也还在。Handler 只是在两者外面再包了一层。

`StageSettler.supports()` 允许一个 Settler 认多种赛制。`ScoredStageSettler.supports` 同时认 `KNOCKOUT` 和 `FREE_MATCH`，但分派方是 Handler 直接注入具体 Settler，全仓库没有任何生产代码调用 `StageSettler.supports`。`supports` 只活在 `StageHooks` 那两个接口上，而那里每种模式最多一个实现，用 `stageMode()` 相等就够了。

### 2.2 Handler 上的钩子大多是空的，有一个从未被调用

`StageHandler` 定义了 `initialize` / `generateMatches` / `beforeStart` / `afterStart` / `resetToDraft`，默认全是空方法。五个实现类：

- 没有一个覆盖 `initialize`、`generateMatches`、`beforeStart`、`resetToDraft`。
- 只有 `ArenaStageHandler.afterStart` 有内容（开第一场）。
- `beforeStart` 在整个仓库没有调用点。

`StageCommands` 仍然在共享逻辑之后调用这些空方法：

```java
public void initialize(InitializeStageBo bo) {
    stageSetupService.initialize(bo);
    handlerOfStage(bo.getStageId()).initialize(bo);   // 永远 no-op
}
public void resetToDraft(Long stageId) {
    stageSettlementService.resetStageToDraft(stageId);
    handlerOfStage(stageId).resetToDraft(stageId);    // 永远 no-op
}
```

`generateMatches` 同样多一次空调用。这是为「以后某种赛制也许要在这里插一脚」留的接口。五种赛制已经都在了，插孔没有被用过。

与此并行，海选的真实校验放在另一套钩子里（`AuditionSetupHook`、`AuditionStartGuard`），不在 Handler 上。两套扩展点，各用一半。

### 2.3 画像表是第二份分派，而且类型比赛制还多

`StageModeProfile` 为了「不要在编排层写 if」，引入了嵌套类型：`Setup`、`Run`、`Result`、`View`、`Trait`、`SeedOrder`、`GeneratePolicy`、`MatchModePolicy`。五行数据，八种类型。

编排层并没有因此去掉分支。`StageSetupService.generateMatchesInternal` 仍按 trait 写了一串海选 / 淘汰特例（必须有圈、按 `teamCount` 兜底座位、解析配对方式、逐选手按号码排序）。`StageRunService.startStage` 读 `profile.rank()`、`REQUIRES_CIRCLES`、`autoStartFirstMatch`、`singleActiveMatch`。`TMatchResultServiceImpl` 继续 `profile.knockout()` / `profile.arena()` / `perCompetitor()`。

画像被大约 10 个生产类读取（结果提交、读模型、裁判端、选手、赛段、生成、开赛、签到）。它作为「五种赛制差在哪」的速查表有用。它没有取代策略，策略也没有取代它。

### 2.4 打分策略是真算法，外面的引擎和另一套枚举不是

三种 `ScoreStrategy` 做的事不同，应该留下计算本身：

- `WinLossDrawStrategy`：校验 1v1 的胜/负/平组合，并按 `outcomeRules` 换成积分。
- `TotalScoreStrategy`：把原始分按 `AggregateRuleEnum` 收成总分再排名。
- `MultiDimStrategy`：先按维度合并多裁判，再按权重合成总分。

`ScoringEngine` 只是一个 `EnumMap` 加「找不到就抛错」。三种模式不会再长出第四种而不改调用方——调用方（`submitResult`）已经按 `MatchModeEnum` 决定走累计还是当场结算。引擎承诺的「加一个 `@Component` 就不用改本类」在判罚入口并不成立。

`ScoreTypeEnum` 与 `MatchModeEnum` 一一对应（`WIN_LOSS_DRAW↔STANDARD`，`TOTAL_SCORE↔VOTING`，`MULTI_DIM↔RANKING`）。`ScoringConfig.type` 的注释指向它，生产代码不调用 `ScoreTypeEnum.fromCode`。聚合实际看的是 `matchMode` 加上 `aggregateRule`。这是一份不会被执行的配置维度。

### 2.5 状态机没有进策略，留在 1500 行的 if 里

策略层接管的是「赛段完成时怎么排名 / 标晋级」和「对阵树长什么样」。场次级判罚全部留在 `TMatchResultServiceImpl`：

- 多裁判票齐才结算、平票加赛、票分左右（`aggregateRefereeVotes`，分数 `1` = 胜、`0.5` = 平）。
- BO3/BO5：没人赢满就加局；提前打出 2:0 则暂存结果并仍开下一局，等导播「提前确认」。
- `publishMode`：`AUTO` 立刻结算，`MANUAL` 写入 `result_json` 等公布，`DIRECTOR` 拒绝裁判提交。淘汰赛读 `knockout.publishMode`，其他赛制读顶层，已经收进 `RuleConfigHolder.resolvePublishMode`，但分支仍在这个类里。
- 淘汰赛同时只允许一场 `GAMING`；取消开始、重置、回滚下游也在这里。

这些规则是产品，不是框架。问题是它们和「写一行分」混在同一个类，策略层帮不上阅读。

同一条「按名次把胜者/败者送进 `DownstreamRouter`」写了两遍：

- `TMatchResultServiceImpl.resolveKnockoutDownstream`（STANDARD 当场结算）。
- `TScoredMatchServiceImpl.resolveKnockoutDownstream`（VOTING/RANKING 在完成赛段时结算）。

后者注释写明必须和前者保持同一口径，否则季军赛座位是空的。这就是复制而不是策略。

票的含义也有第三处展示口径：`TMatchServiceImpl` 为导播列表重数 `leftVotes` / `rightVotes`（同样认 `action=VOTE` 且分数为 1 或 0.5）。读模型可以留，但「1 分是一票胜」这个规则不该再长出第四份。

### 2.6 空生成器与死工厂

`FreeMatchGenerator.generate` 固定返回 0 场。自由对抗的画像是 `GeneratePolicy.SKIP_SILENT`，`StageSetupService` 在调用 `generateBracket` 之前就 return 了。`FreeMatchStageHandler` 也不持有这个生成器。它只为了让 `StageGeneratorFactory` 的覆盖表上有一行。

`StageGeneratorFactory.generate` 无生产调用。覆盖测试可以改成直接看三个真正会建树的类，不必留一个工厂。

### 2.7 哪些抽象是已经做对的，不要当成过度设计拆掉

这些类短、只有一个职责，而且注释写明了它们消灭过的分叉。简化方案保留它们：

| 类 | 为什么留 |
| --- | --- |
| `flow/MatchStateWriter` | 场次和全部轮次一起改状态。漏写轮次曾经让「场次已结算、轮次仍 GAMING」 |
| `flow/DownstreamRouter` | `promotion_rule` 的唯一路由。胜者占位「先更新、没有就补插」 |
| `flow/CompetitorOutcomeWriter` | `outcome_status` / `final_rank` 的唯一写入口 |
| `flow/JudgeCompletenessChecker` | 「每名选手被每位应到裁判打过」的唯一口径。0 分算已判，退赛不算未判 |
| `flow/ParticipantScoreWriter` | 一场一条 SQL 回写 participant |
| `flow/MatchRoundLocator` | 多局时「当前生效轮」只此一处 |
| `engine/scoring/ScoreAggregator`、`RankCalculator` | 无状态纯函数 |
| `KnockoutGenerator` 里的种子位 / 轮空排布 | 这是算法，不是框架。可以去掉 `StageGenerator` 接口，算法留下 |
| `AuditionStageSettler` 的加赛生成 | 产品规则，搬进海选类，不要重写规则 |
| `service/impl/roster/*` | 名单边。判罚只调用，不重做 |

`TStageLifecycleServiceImpl` 把一个上帝类拆成 Setup / Run / Settlement / Bye / CheckIn / Arena / FreeMatch，这个方向是对的。要删的是它外面的 `StageCommands` 和 Handler 空壳，不是把这些服务再并回一个文件。

---

## 3. 简化目标模型

目标：读一场 battle 的人只需要打开两个地方——状态怎么变（`MatchFlow`），这种赛制完成时怎么排名（对应的 `*Mode`）。不再为了「以后也许有第六种赛制」维护四套注册表。

### 3.1 保留的产品行为（行为冻结清单）

简化前后必须一致，现有 `src/test/java/com/dance/street/game/stage/*` 就是验收：

- 五种赛制都能走完：生成（或明确不生成）→ 开赛 → 判罚 → 胜者 / 晋级名单。
- 赛段 `DRAFT/GAMING/SETTLED/DISCARD`，场次与轮次 `PENDING/GAMING/SETTLED`。字符串不改。
- 海选：空圈、签到落圈、按圈裁判、完成赛段才排名、同分加赛（最多现有的轮数上限）、加赛可打分或导播指定。
- 排名赛：多维权重合计 100 的开赛校验、BATCH 打完自动公布、同分超名额保持 `PENDING` 给 `adjustAdvancement`。
- 淘汰赛：一轮一个赛段、轮空由导播点开始才结算、同时一场进行中、已投票不允许被静默清掉、BO1/BO3/BO5、季军赛走 `promotion_rule` 的 `"2"`、`MANUAL` 公布与 `DIRECTOR` 代判。
- 擂台：不预生成树、开赛自动第一场、守擂 / 队尾 / 弃权 / 临时弃权、平局积分开关。
- 自由对抗：手动加场、删场、手选晋级者。
- 撤销：下游已消费则拒绝；入口海选保留圈和落圈，只清判罚。
- HTTP 路径、请求体、SSE 的 `type` 字符串不变。

### 3.2 目标形状

```
controller/*                         不变
  ITStageLifecycleService            契约不变
    StageLifecycle                   今天的 TStageLifecycleServiceImpl
      StageSetup / StageRun / StageSettlement / Bye / CheckIn   保留，去掉对 Handler/Hooks 的依赖
      mode/AuditionMode              建圈 + 开赛校验 + 结算加赛（合并 Generator、两个 Hook、AuditionStageSettler）
      mode/RankMode
      mode/KnockoutMode              种子位计划 + 完成赛段时的「场次都结束了吗」
      mode/ArenaMode                 结算名次；开赛首场直接调 ArenaRunService
      mode/FreeMatchMode             完成条件与淘汰赛相同，不单独做空生成器
      MatchFlow                      从 TMatchResultServiceImpl 拆出的场次状态
        ScoreSheet                   写 t_round_score（海选逐人、累计打分共用）
        VoteTally                    多裁判票与 BO 局分
        Scoring                      switch(matchMode) 调用三个现有计算类
        DownstreamRouter.routeRanked 唯一的「按名次送下游」
      flow/* 写入口                   保留
```

`StageMode` 不做成大接口。五种赛制差在算法，不差在同一组方法。建议只共享三件事，其余用具体类：

```java
/** 一种赛制。编排用 stageMode 找到它，不经过第二张画像表。 */
public interface StageMode {
    StageModeEnum mode();
    /** 纯计算。自由对抗 / 擂台不实现，调用方先看 ModeFlags。 */
    default BracketPlan plan(List<Long> seededIds, RuleConfigHolder rule) {
        throw new ServiceException("赛制[{}]不生成对阵", mode().getCode());
    }
    StageSettleOutcome settle(TStage stage);
}
```

布尔差异收成枚举上的方法，替换 `StageModeProfile` 那八个嵌套类型。五个值，方法写死，读起来就是一张表：

```java
public boolean generatesBracket() { return this == AUDITION || this == RANK || this == KNOCKOUT; }
public boolean perCompetitor()    { return this == AUDITION || this == RANK; }
public boolean singleActiveMatch(){ return this == KNOCKOUT; }
public boolean autoStartFirstMatch(){ return this == ARENA; }
```

`StageSetupService` 里那些 `profile.setup().has(Trait.XXX)` 改成对具体模式的直接调用（`if (mode == AUDITION) auditionMode.validateCircles(...)`），或者把该段准备逻辑挪进 `AuditionMode.prepare(...)`。不要再发明 `Trait`。

打分收成一个类、一个 switch，三个计算类保持 public、无状态，单测继续 `new`：

```java
public List<MatchScoreResult> compute(MatchScoreInput input) {
    return switch (input.getMatchMode()) {
        case STANDARD -> winLoss.score(input);
        case VOTING   -> total.score(input);
        case RANKING  -> multiDim.score(input);
    };
}
```

删掉 `ScoreStrategy` 接口和 `ScoringEngine` 的 Map 注册。三个类可以不再 `implements` 任何东西。`ScoreTypeEnum` 删除；`scoring.type` 反序列化时忽略（`ScoringConfig` 已有 `ignoreUnknown`，删字段即可，旧 JSON 不会炸）。

`MatchFlow.submit` 的骨架应该能在一屏内看完，模式差异是有名字的方法，不是画像布尔：

```java
public MatchResultVo submit(SubmitResultBo bo) {
    TMatch match = loadGaming(bo.getMatchId());
    TStage stage = stages.get(match.getStageId());
    StageModeEnum mode = StageModeEnum.fromCode(stage.getStageMode());
    if (mode.perCompetitor()) {
        return scoreSheet.writePerCompetitor(match, stage, bo);   // 保持 GAMING
    }
    if (isAccumulating(match)) {
        return scoreSheet.accumulate(match, stage, bo);            // VOTING/RANKING，完成赛段再结算
    }
    return voteTally.submitAndMaybeSettle(match, stage, bo);       // STANDARD：票、BO、公布、下游
}
```

`voteTally` 内部仍会处理淘汰赛 BO 和擂台 MANUAL。那是两种产品规则，用两个私有方法，不要再包策略接口。

### 3.3 一场 battle 在目标模型里的数据流

```
开始赛段
  StageRun：DRAFT→GAMING
  mode == KNOCKOUT → KnockoutMode.plan → 落 PENDING 场次
  mode == ARENA    → ArenaRunService.startNextArenaMatch
  mode == AUDITION → AuditionMode.assertReady（落圈 + 每圈有裁判）后场次直接 GAMING

提交判罚
  AUDITION/RANK → ScoreSheet 覆盖写 t_round_score，场次保持 GAMING，SSE "scores"
  STANDARD      → VoteTally 记一票
                    人未齐 → 返回，场次仍 GAMING
                    人齐且平票 → 加一局
                    人齐且分出胜负 → Scoring.compute(STANDARD)
                      MANUAL → result_json，仍 GAMING
                      否则 MatchStateWriter SETTLED
                           DownstreamRouter.routeRanked
                           roster.syncPreAdvance
                           SSE "match"

完成赛段
  AuditionMode.settle / RankMode.settle / ...
  不能结束 → StageSettleOutcome.pending / tiebreaker（不抛异常）
  能结束 → stage GAMING→SETTLED，roster.rebuildEntriesOfDownstream
```

名单边仍是晋级的事实来源。`DownstreamRouter` 只填本赛段对阵树里的下游座位（淘汰赛同一轮内的占位，以及决赛标 `ADVANCE`）。跨赛段带人继续走现有 roster，本文不设计新的「晋级框架」。

---

## 4. 对比

| | 现在 | 简化后 |
| --- | --- | --- |
| 一种赛制要登记的地方 | 枚举 + 画像行 + Handler + Settler +（可选）Generator +（可选）Hook + 工厂覆盖测试 | 枚举上的几个布尔方法 + 一个 `*Mode` 类 |
| 分派注册表 | `StageHandlerRegistry`、`StageGeneratorFactory`、`StageHooks`、`ScoringEngine` 四套 Spring List | 一个 `Map<StageModeEnum, StageMode>`，打分用 switch |
| 空扩展点 | `beforeStart` 无调用；`initialize` / `generateMatches` / `resetToDraft` 无实现仍被调用；`FreeMatchGenerator` 返回空计划；`StageSettler.supports` 无调用 | 删除 |
| 场次判罚 | `TMatchResultServiceImpl` 1502 行，策略层进不去 | `MatchFlow` + `ScoreSheet` + `VoteTally`，各自几百行以内，规则还是这些规则 |
| 淘汰赛下游 | 两份 `resolveKnockoutDownstream` | `DownstreamRouter.routeRanked` 一份 |
| 打分类型 | `MatchModeEnum` 与 `ScoreTypeEnum` 重复 | 只留 `MatchModeEnum` |
| 画像 | 8 个嵌套类型，和 Handler 能力重复 | 枚举方法。读模型继续问枚举，不再问 Handler |
| 纯计算 | `KnockoutGenerator` / 三个 Strategy / `ScoreAggregator` | 留下，去掉接口与自动注册 |
| 写入口 | `MatchStateWriter` 等 | 不动 |
| 对外行为 | — | 冻结。测试仍是 `stage/*`、`engine/scoring/*` |

行数不是目标。目标是「改海选加赛只打开 `AuditionMode`，改 BO 只打开 `VoteTally`，改胜者去向只打开 `DownstreamRouter`」。

---

## 5. 分阶段迁移

每一期单独可上线，上一期没做完不要开始下一期。验收都是现有测试，不先写新框架的测试。

重点回归（已有，不要重写用例）：

- `stage/MatchResultStateFlowTest`：投票、平票、MANUAL、取消开始、BO。
- `stage/KnockoutStateFlowTest`、`KnockoutByeSlotTest`、`KnockoutTopByeChainE2ETest`。
- `stage/Audition*Test`、`TiebreakerMatchTypeTest`。
- `stage/RankSettlementTest`、`ScoredMatchSettlementTest`。
- `stage/ArenaFreeMatchFlowTest`、`FullLifecycleE2ETest`、`TemplateFullLifecycleE2ETest`。
- `engine/scoring/ScoringEngineTest`：改完后直接测三个计算类，断言保持不变。
- `stage/HandlerCoverageTest`、`StageModeProfileCoverageTest`、`engine/EngineCoverageTest`：随被删类型一起改或删，不要为了覆盖率留空壳。

### 阶段 1 — 删死代码，把重复收成一处

不改状态机顺序，不改类的对外方法名（Controller 无感）。

1. 删 `StageGeneratorFactory` 与 `FreeMatchGenerator`。`EngineCoverageTest` 改为断言 `AuditionGenerator` / `RankGenerator` / `KnockoutGenerator` 三个 Bean 存在即可。
2. `StageCommands` 里去掉对 `initialize` / `generateMatches` / `resetToDraft` 的空调用。`startStage` / `completeStage` 保留转发，避免这一期大搬家。
3. 删 `StageHandler.beforeStart`（无调用）。确认没有实现覆盖另外三个空钩子后再从接口去掉，调用点同步删。
4. `StageHooks` 只服务海选。把 `AuditionSetupHook.validateBeforeGenerate` 与 `AuditionStartGuard.assertPostGenerate` 变成 `AuditionMode` 上的两个 public 方法（这一期可以先是一个普通 `@Component`，不必等阶段 2 的接口）。`StageSetupService` / `StageRunService` 直接注入并在 `stageMode == AUDITION` 时调用。删除 `StageSetupHook`、`StageStartGuard`、`StageHooks`。
5. 把两份 `resolveKnockoutDownstream` 收成 `DownstreamRouter.routeRanked(match, results)`。`TMatchResultServiceImpl` 用返回值决定要不要 `syncPreAdvance`（现在只有 STANDARD 路径会同步；累计打分路径在完成赛段时由 `rebuildEntriesOfDownstream` 覆盖。合并时保持这个差别，用参数 `boolean` 或两个薄包装，不要顺便「统一成总是 sync」）。
6. 删 `ScoreTypeEnum` 及 `ScoringConfig.type` 字段。前端若还提交 `scoring.type`，后端忽略；确认 `frontend/src` 没有把它当分支条件。若前端表单还在写这个字段，这一期只停后端读取，字段删除放到前端不再提交之后。
7. `StageSettler.supports` 若阶段 1 还留着 Settler，删掉该方法，两个 Handler 继续直接注入 `ScoredStageSettler`。

风险：第 5 步。两份下游路由「看起来一样」，差在返回值与是否推 roster。合并前用 `ScoredMatchSettlementTest` 和 `KnockoutStateFlowTest` 对一下季军赛和 `syncPreAdvance`。

不做：不动 `TMatchResultServiceImpl` 的提交顺序，不动 Settler 内部算法。

### 阶段 2 — 一种赛制一个类，拆掉 Handler / Settler / Generator 接口

1. 建立 `mode/AuditionMode` 等五个类，把现有 Generator 的 `generate` 与 Settler 的 `settle` 移进去。种子位、加赛、擂台队列的方法体原样搬迁，不重写。
2. `StageSetupService` 在 `generatesBracket()` 为真时调用 `modes.of(stage).plan(...)`。`StageSettlementService.completeStage` 调用 `modes.of(stage).settle(stage)`，状态翻转和广播留在 Settlement。
3. 删除 `StageHandler`、五个 Handler、`StageHandlerRegistry`、`StageCommands`（生命周期门面直接调 Setup/Run/Settlement）、`StageGenerator` 接口、`StageSettler` 接口。
4. 用 `StageModeEnum` 上的方法替换 `StageModeProfile`。调用面大约 10 个生产类，按编译错误改，不保留适配层（适配层就是今天的 `capabilities()`）。
5. `ArenaStageHandler.afterStart` 变成 `StageRunService` 里的一行：`if (mode.autoStartFirstMatch()) arenaRunService.startNextArenaMatch(stageId)`。

风险热点：

- `StageModeProfiles` 的 `SeedOrder`（海选 / 排名按号码，淘汰按种子，擂台先种子再号码）今天被 Setup 用来排序。搬到枚举方法时把比较器一起搬走，并跑 `TemplateAuditionCircleTest`、`ArenaPreBracketTest`。
- 海选「配置了圈但还没人」时跳过自动初始化、预建空圈。这段在 `StageSetupService`，不要在搬家时并进普通 initialize。
- 完成赛段的乐观锁（`status=GAMING` 条件更新）留在 `StageSettlementService`，不要放进 Mode。Mode 继续遵守「不改 `t_stage.status`」。
- 覆盖测试 `HandlerCoverageTest`、`StageSettlementStrategyTest`、`StageModeProfileCoverageTest` 改成对五个 Mode Bean 的断言。

### 阶段 3 — 拆开判罚大类，不去改规则

只拆 `TMatchResultServiceImpl`，对外仍是 `ITMatchResultService`。

| 新类 | 从现在的方法搬来 | 不包含 |
| --- | --- | --- |
| `ScoreSheet` | `writePerCompetitorScores`、`writeRawScores`、`TScoredMatchServiceImpl.accumulateScores` 的写行部分 | 结算、下游 |
| `VoteTally` | `collectRefereeVotes`、`aggregateRefereeVotes`、`resolveKnockoutSeries`、`holdForManualPublish`、`publishResult` | 海选逐人打分 |
| `MatchFlow` | `submitResult` 骨架、`startMatch`、`cancelStartMatch`、`resetMatch`、`resetRound`、`settleAndAdvance` | 排名算法 |

`TScoredMatchServiceImpl.settleScoredMatches` 留在排名 / 淘汰的完成路径上，但算分改为调 `Scoring.compute`，下游改为 `routeRanked`。可以保留这个类，它已经够小（228 行）。

`ScoringEngine` 换成第 3.2 节的 switch。`ScoringEngineTest` 改为直接构造三个计算类。

风险热点（这期最容易改出行为差）：

- BO「提前打出胜负仍开下一局，同时把整场结果放进待确认」。`advanceToNextRound` 与 `settleDecidedSeries` 必须原样搬家，包括「打满 `maxGames` 才自动结算」。
- `MANUAL` 把结果放在 `t_match.result_json`，重置必须清掉（现有注释：不清的话下一次公布会重放旧结果）。
- 擂台重投计数是 Redis key `arena:revote:{matchId}`，不在表状态里。`settleAndAdvance` / `reset` 会删它。拆类时把删除留在结算和重置两条路径上。
- 淘汰赛 `startMatch` 拒绝切换到新场，如果另一场已经有票。这个守卫不要放进通用 `MatchStateWriter`。
- 海选重复提交必须按「整场所有回合」删该裁判旧分，只删当前回合会把分加双（类上现有注释）。`ScoreSheet` 保留这条删除范围。
- `submitResult` 开头剥掉客户端逐条 `refereeId`，以服务端 `bo.refereeId` 为准。留在 `MatchFlow` 入口，不要下放到每个计算类。

阶段 3 完成的标志：`TMatchResultServiceImpl` 删除或变成不到一屏的委托；`rg "resolveKnockoutDownstream"` 只剩 `DownstreamRouter`；一次淘汰赛提交的调用栈不超过 Controller → `MatchFlow` → `VoteTally` / `Scoring` / `DownstreamRouter`。

---

## 6. 不做的事

这些要么是别的子系统，要么是「顺手重构」会把三期拖成重写。

- 不改表、不改状态字符串、不引入状态机库（Spring Statemachine 或自研 DSL 都不要）。现有字符串和条件更新已经够用，缺的是少几层转发。
- 不改 HTTP、VO、`SubmitResultBo`、SSE 的 `type` 值。前端导播台、裁判端、大屏继续用现在的 URL。
- 不动名单边：`service/impl/roster/*`、`TStageRoster*`、`RosterEdgeRules`、中间态确认 / 跳过 / 拖动占座。判罚只继续调用 `syncPreAdvance`、`rebuildEntriesOfDownstream`、`settlePendingSeatsOfDownstream`、`assertStageStartable`。
- 不动 `StageChain`（`next_stage_id` 是显示链，不是判罚机）。
- 不动可视化：`TVisScene/Screen/Widget`、大屏控制。
- 不重写算法：淘汰种子位、海选加赛轮次上限与圈内晋级名额、擂台队列与积分、排名加权、轮空补位、`JudgeCompletenessChecker` 的「应到裁判」顺序（场次绑定优先，否则赛段裁判，都没有则按 1）。
- 不把 `TMatchServiceImpl`（约 1080 行，主要是列表和票数展示）并进这次拆分。阶段 3 之后如果票数展示与 `VoteTally` 口径再次分叉，再单开一次，只抽「怎么数票」。
- 不把五种赛制合成一个配置驱动引擎。`ruleConfig` 继续承载圈数、BO、维度、公布方式；不要再加一层「用 JSON 描述状态转移」。
- 不加新的 Handler / Plugin / Visitor。阶段 2 的 `StageMode` 接口只有 `mode`、`plan`、`settle`。开赛副作用（擂台第一场、海选守卫）写在 `StageRunService` 的显式 if 里。
- 不改权限、租户、签到接口的 URL。签到落圈仍由 `StageCheckInService` 做，只是它不再为了问「是不是海选」去找 Handler。
- 本 PR 不改任何引擎代码。实现从阶段 1 的删除项开始，单独开 PR。

---

## 7. 建议的决策

可以先做阶段 1。它删除的是无调用工厂、空钩子和一份重复的下游路由，不改变导播操作顺序，评审面小。

阶段 2 是结构决定：接受「五种赛制就是五个类，布尔写在枚举上」。若希望保留 `StageModeProfiles` 当文档式配置，也可以只做阶段 1 和阶段 3，把画像表留下。不建议画像表和 Handler 长期并存——那就是现状。

阶段 3 收益最大，也最容易出行为差，放在死代码已经删掉之后再拆。
