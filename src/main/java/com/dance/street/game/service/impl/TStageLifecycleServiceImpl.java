package com.dance.street.game.service.impl;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import com.dance.street.game.excel.ExcelUtil;
import com.dance.street.game.engine.common.PairingModeResolver;
import com.dance.street.game.engine.common.SnowflakeJson;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.MatchPlan;
import com.dance.street.game.engine.generator.SlotPlan;
import com.dance.street.game.engine.generator.StageGeneratorFactory;
import com.dance.street.game.engine.scoring.ScoreAggregator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import com.dance.street.game.service.impl.flow.DownstreamRouter;
import com.dance.street.game.service.impl.flow.MatchStateWriter;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.settle.ArenaQueueSupport;
import com.dance.street.game.service.impl.settle.AuditionStageSettler;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import com.dance.street.game.service.impl.settle.StageSettlerRegistry;
import static com.dance.street.game.service.impl.settle.SettlementSupport.parseCompetitorNumber;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 赛段生命周期编排实现。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TStageLifecycleServiceImpl implements ITStageLifecycleService {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TPlayerMapper playerMapper;
    private final TRefereeMapper refereeMapper;
    private final TTournamentMapper tournamentMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final ITStageService stageService;
    private final StageGeneratorFactory generatorFactory = new StageGeneratorFactory();
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ITRefereeStageService refereeStageService;
    private final ITStageRosterService rosterService;
    /** 赛段级结果(晋级/淘汰/名次)的唯一写入口 */
    private final CompetitorOutcomeWriter outcomeWriter;
    /** 结算策略注册表:按赛制分派(见 settle 包) */
    private final StageSettlerRegistry settlerRegistry;
    /** 海选结算策略:加赛自动结算由它承担 */
    private final AuditionStageSettler auditionStageSettler;
    /** 擂台赛轮转队列/积分口径(查询与结算共用) */
    private final ArenaQueueSupport arenaQueueSupport;
    /** 结算共享内核(场次置结算、号码排序等) */
    private final SettlementSupport settlementSupport;
    /** 赛段链唯一入口:开赛守卫用它校正 prev 展示列 */
    private final StageChain stageChain;
    /** 当前上场选手标记的跨实例存储(Redis,单机降级为进程内) */
    private final MatchCurrentCompetitorStore currentCompetitorStore;
    /** 场次状态推进唯一入口(场次 + 轮次成套写) */
    private final MatchStateWriter matchStateWriter;
    /** 淘汰链下游路由唯一入口(轮空胜者与正常胜者共用同一套去向逻辑) */
    private final DownstreamRouter downstreamRouter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initialize(InitializeStageBo bo) {
        TStage stage = mustGetStage(bo.getStageId());
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,不可重复执行");
        }

        List<TCompetitor> comps;
        if (bo.getCompetitorIds() != null && !bo.getCompetitorIds().isEmpty()) {
            comps = competitorMapper.selectByIds(bo.getCompetitorIds());
        } else {
            LambdaQueryWrapper<TCompetitor> q = Wrappers.lambdaQuery();
            q.eq(TCompetitor::getStageId, stage.getId());
            q.eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode());
            comps = competitorMapper.selectList(q);
        }
        if (comps == null || comps.isEmpty()) {
            throw new ServiceException("赛段无可初始化的参赛方");
        }

        // 海选/排名赛:按签到号码数值升序写 seedRank(号码即种子顺序,round 生成/落位以此为准);
        // 擂台赛:已有显式 seedRank(GUEST 落位/手动预排)保持原顺序在前,其余签到选手按号码升序;
        // 其余赛制按原 seedRank 升序(空值排最后)
        boolean perCompetitorInit = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        if (perCompetitorInit) {
            comps.sort(Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber())));
        } else if (StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            comps.sort(Comparator
                .comparing((TCompetitor c) -> c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank())
                .thenComparingInt(c -> parseCompetitorNumber(c.getNumber())));
        } else {
            comps.sort(Comparator.comparing(c -> c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank()));
        }
        // 种子顺位批量写:此前逐个 updateById(100 人 = 100 条 SQL)
        List<Map<String, Object>> seedItems = new ArrayList<>(comps.size());
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            c.setSeedRank((long) (i + 1));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("seedRank", c.getSeedRank());
            seedItems.add(item);
        }
        if (!seedItems.isEmpty()) {
            competitorMapper.batchUpdateSeedRank(seedItems);
        }

        stage.setIsInitialized(1L);
        // 业务状态收敛为 规划中(DRAFT) → 进行中(GAMING) → 已结束(SETTLED):
        // 初始化只锁定名单/排种子,不再进入「未开始(PENDING)」中间态
        stageMapper.updateById(stage);
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMatches(GenerateMatchesBo bo) {
        generateMatchesInternal(bo, false);
    }

    /**
     * 生成对阵内部实现。
     *
     * @param randomSplit 海选分圈时是否随机抽取(true 时选手随机分到各圈场次)
     */
    @Transactional(rollbackFor = Exception.class)
    private void generateMatchesInternal(GenerateMatchesBo bo, boolean randomSplit) {
        TStage stage = mustGetStage(bo.getStageId());
        // 擂台赛不生成对阵树:开始赛段后由导播台按轮转队列逐场创建对决
        if (StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("擂台赛不生成对阵,开始赛段后由导播台逐场创建对决");
        }
        // 自由对抗不生成对阵:对手由线下抽签/指认,场次由导播台手动添加(不影响已加场次)
        if (StageModeEnum.FREE_MATCH.getCode().equals(stage.getStageMode())) {
            log.info("自由对抗赛段[{}]不生成对阵,场次由导播台手动添加", stage.getId());
            return;
        }
        // 已结束/已取消的赛段不允许再生成对阵
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]已结束,无法生成对阵", stage.getName());
        }
        boolean isAudition = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode());
        boolean isRank = StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        // 海选赛/排名赛:逐选手轮次,允许跳过显式初始化(兜底:自动初始化)
        boolean perCompetitorRound = isAudition || isRank;
        // 海选圈默认为空:生成对阵/开始前必须至少配置一圈(在赛段配置中新增:人数/裁判/去向)
        if (isAudition && plannedCircleCount(stage) < 1) {
            throw new ServiceException("海选尚未配置圈,请先在赛段配置中新增至少一圈(人数/裁判/去向)");
        }
        if (!perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段尚未初始化,请先 initialize");
        }
        // 海选赛/排名赛允许跳过显式初始化(兜底:自动初始化)
        if (perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            // 海选已配置圈(单圈也算)但当前无人签到(预建空圈)时跳过自动初始化,
            // 允许抽号前先生成按配置的空圈结构,待签到后再落圈;
            // 其余场景保持原逻辑(初始化会把名单锁定,不改变业务状态)
            boolean emptyPlannedAudition = isAudition && plannedCircleCount(stage) >= 1
                && competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, stage.getId())
                    .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode())) == 0;
            if (!emptyPlannedAudition) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stage.getId());
                initialize(initBo);
            } else {
                log.info("海选赛段[{}]暂无人签到,跳过自动初始化,按配置预建空圈", stage.getId());
            }
        }
        // 未开赛前允许重新生成(圈数/规则变更后重排):已有对阵但全部仍为 PENDING 时,
        // 先记录待清除,待配置校验通过后再清旧重建;已有场次开始则拒绝
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
        boolean hasOldMatches = exist > 0;
        if (hasOldMatches) {
            long started = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .ne(TMatch::getStatus, StageConstants.MATCH_PENDING));
            if (started > 0) {
                throw new ServiceException(
                    "赛段对阵已生成且已有场次开始,无法重新生成;如需重排请先重置为草稿");
            }
        }
        if (StringUtils.isNotBlank(bo.getRuleConfig())) {
            stage.setRuleConfig(bo.getRuleConfig());
            stageMapper.updateById(stage);
        }

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc != null) {
            rc.setRandomSplit(randomSplit);
        }
        // 海选分圈:持久化本次分圈方式(按号顺序均分 / 随机抽取),
        // 签到/补签需要据此判断新选手应按号码落圈还是按名额均衡落圈
        if (isAudition) {
            persistCircleSplitMode(stage, randomSplit);
        }
        StageModeEnum mode = StageModeEnum.fromCode(stage.getStageMode());
        // 承接上一淘汰赛胜者:按胜者位置顺序配对(SEQUENTIAL),不受本赛段 SEED 配置影响;
        // 从海选赛进入的淘汰赛,未显式配置时默认标准种子对位(1-16、2-15)
        if (StageModeEnum.KNOCKOUT.equals(mode)
            && rc != null && rc.getKnockout() != null) {
            // 与预排(getPreBracket)口径一致:prev_stage_id 缺失时按 next 指针反查上一赛段,
            // 避免链表指针正常但 prev_stage_id 为空时误走 SEED 头尾交叉
            TStage prev = stageService.resolvePrevStage(stage);
            if (prev != null && StageModeEnum.KNOCKOUT.getCode().equals(prev.getStageMode())) {
                rc.getKnockout().setPairingMode("SEQUENTIAL");
            } else if (StringUtils.isBlank(rc.getKnockout().getPairingMode())
                && prev != null
                && (StageModeEnum.AUDITION.getCode().equals(prev.getStageMode())
                    || StageModeEnum.RANK.getCode().equals(prev.getStageMode()))) {
                rc.getKnockout().setPairingMode("SEED");
            }
        }
        String matchMode;
        if (StageModeEnum.AUDITION.equals(mode)) {
            matchMode = MatchModeEnum.VOTING.getCode();
        } else if (StageModeEnum.RANK.equals(mode)) {
            matchMode = MatchModeEnum.RANKING.getCode();
        } else {
            matchMode = (rc != null && rc.getScoring() != null && rc.getScoring().getMatchMode() != null)
                ? rc.getScoring().getMatchMode() : MatchModeEnum.STANDARD.getCode();
        }

        // 按种子顺位取参赛方(海选赛按签到号码顺序)
        LambdaQueryWrapper<TCompetitor> cq = Wrappers.lambdaQuery();
        cq.eq(TCompetitor::getStageId, stage.getId());
        // 退赛选手不进入对阵(初始化时也会被排除,生成时再兜底过滤一次)
        cq.ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode());
        if (perCompetitorRound) {
            cq.orderByAsc(TCompetitor::getNumber);
        } else {
            cq.orderByAsc(TCompetitor::getSeedRank);
        }
        List<TCompetitor> comps = competitorMapper.selectList(cq);
        // 海选/排名赛:按签到号码的数值排序(抽签号码决定上场/落位顺序),
        // 数据库字符串排序在号码不补零时会错位(如 10 < 2),这里统一按数值重排
        if (perCompetitorRound) {
            comps.sort(Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber())));
        }
        // 防御:seedRank 为 NULL 的参赛方(异常数据)排到最后,避免数据库 NULL 先序
        // 占位后又被打乱导致对阵出现空槽
        if (!perCompetitorRound) {
            comps.sort(Comparator.comparing((TCompetitor c) ->
                c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank()));
        }
        // 海选分圈提前校验:晋级名额必须能被"实际圈数"整除(实际圈数=min(配置圈数,参赛人数)),
        // 生成时就报错,避免拖到完成结算时才暴露配置错误
        if (isAudition) {
            int advanceCount = StageFlowSupport.readStageAdvanceCount(stage);
            int cfgCircles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
            List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
            boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
            if (explicitQuota) {
                // 每圈独立晋级名额:只校验非负,不要求均分
                for (Integer q : perCircleCfg) {
                    if (q == null || q < 0) {
                        throw new ServiceException("每圈晋级人数配置非法(不能为负): {}", perCircleCfg);
                    }
                }
            } else if (advanceCount > 0 && advanceCount % cfgCircles != 0) {
                throw new ServiceException("海选总晋级数[{}]无法按{}圈均分,请调整晋级名额或圈数",
                    advanceCount, cfgCircles);
            }
        }
        // 配置校验通过后,清除旧对阵重新生成(未开赛场景)
        if (hasOldMatches) {
            clearStageMatches(stage.getId());
            log.info("赛段[{}]对阵已存在,未开赛前重新生成:已清除旧对阵", stage.getId());
        }
        // 保留原始种子位置:按 seedRank 落位,跳过场次/缺位留空,避免后续胜者抢占被跳过场次的位置
        long maxSeed = comps.stream().map(TCompetitor::getSeedRank).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        // 淘汰赛按赛段计划规模(teamCountStart)兜底:人数不足时仍生成完整 bracket,缺位以轮空结算,
        // 与预排(prebracket)及前端对战树预览保持一致;其余赛制不受影响
        long plannedSlots = StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())
            && stage.getTeamCountStart() != null ? stage.getTeamCountStart() : 0L;
        int slotCount = (int) Math.max(Math.max(comps.size(), Math.min(maxSeed, 4096L)), Math.min(plannedSlots, 4096L));
        List<Long> seededIds = new ArrayList<>(Collections.nCopies(slotCount, null));
        int nextFree = 0;
        for (TCompetitor c : comps) {
            int idx = c.getSeedRank() != null && c.getSeedRank() > 0 && c.getSeedRank() <= slotCount
                ? (int) (c.getSeedRank() - 1) : -1;
            if (idx < 0) {
                while (nextFree < slotCount && seededIds.get(nextFree) != null) {
                    nextFree++;
                }
                idx = nextFree < slotCount ? nextFree : -1;
            }
            if (idx >= 0) {
                seededIds.set(idx, c.getId());
            }
        }

        // 淘汰赛:补齐空缺的配对方式,并统一到与中间态/大屏一致的判定口径
        if (StageModeEnum.KNOCKOUT.equals(mode) && rc != null) {
            normalizePairingMode(stage, rc);
        }
        BracketPlan plan = generatorFactory.generate(mode, seededIds, rc);

        List<MatchPlan> sorted = new ArrayList<>(plan.getMatches());
        sorted.sort(Comparator.comparingInt(MatchPlan::getRound).thenComparingInt(MatchPlan::getMatchIndex));

        // 第一遍:建 TMatch + TMatchRound + TMatchParticipant,记录 (round,index) -> matchId
        Map<String, Long> matchKeyToId = new HashMap<>();
        for (MatchPlan mp : sorted) {
            TMatch m = new TMatch();
            m.setTournamentId(stage.getTournamentId());
            m.setStageId(stage.getId());
            m.setName(mp.getName());
            m.setDisplayRow(mp.getDisplayRow() == null ? null : mp.getDisplayRow().longValue());
            m.setDisplayCol(mp.getDisplayCol() == null ? null : mp.getDisplayCol().longValue());
            m.setDisplayZone(mp.getDisplayZone());
            m.setStatus(StageConstants.MATCH_PENDING);
            m.setMatchMode(matchMode);
            m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
            matchMapper.insert(m);
            matchKeyToId.put(matchKey(mp.getRound(), mp.getMatchIndex()), m.getId());

            // 海选赛/排名赛:每个选手一个轮次,按上场顺序
            if (perCompetitorRound) {
                int seq = 1;
                for (SlotPlan slot : mp.getSlots()) {
                    TMatchRound round = new TMatchRound();
                    round.setTournamentId(stage.getTournamentId());
                    round.setMatchId(m.getId());
                    round.setRoundSequence((long) seq);
                    round.setCompetitorId(slot.getCompetitorId());
                    round.setStatus(StageConstants.MATCH_PENDING);
                    matchRoundMapper.insert(round);
                    seq++;
                }
            } else {
                TMatchRound round = new TMatchRound();
                round.setTournamentId(stage.getTournamentId());
                round.setMatchId(m.getId());
                round.setRoundSequence(1L);
                round.setStatus(StageConstants.MATCH_PENDING);
                matchRoundMapper.insert(round);
            }

            for (SlotPlan slot : mp.getSlots()) {
                // 轮空位(无参赛方):不落 participant 行,由 settleByeMatches 按真实人数判断单/双边轮空
                // (t_match_participant.competitor_id 为 NOT NULL,不能插入 null 占位)
                if (slot.getCompetitorId() == null) {
                    continue;
                }
                TMatchParticipant p = new TMatchParticipant();
                p.setTournamentId(stage.getTournamentId());
                p.setMatchId(m.getId());
                p.setCompetitorId(slot.getCompetitorId());
                p.setDisplaySlotIndex((long) slot.getSlotIndex());
                p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
                participantMapper.insert(p);
            }
        }

        // 第二遍:按下游引用回填 promotion_rule(用真实 matchId)。海选赛跳过,由 completeStage 结算晋级
        if (!perCompetitorRound) {
            for (MatchPlan mp : sorted) {
                // 无胜者去向的场次(如小组赛积分制,按组累计晋级)不写 promotion_rule,
                // 否则 winnerTargetRound 为 null 会在下方 unboxing 时 NPE
                if (!mp.isFinalMatch() && mp.getWinnerTargetRound() == null) {
                    continue;
                }
                PromotionTarget target = new PromotionTarget();
                if (mp.isFinalMatch()) {
                    target.setAction(StageConstants.ACTION_FINAL_ADVANCE);
                } else {
                    target.setAction(StageConstants.ACTION_ADVANCE);
                    target.setTargetMatchId(matchKeyToId.get(matchKey(mp.getWinnerTargetRound(), mp.getWinnerTargetMatchIndex())));
                    target.setTargetSlot(mp.getWinnerTargetSlot());
                }
                Map<String, PromotionTarget> rule = new java.util.LinkedHashMap<>();
                rule.put("1", target);
                // 季军赛:半决赛败者路由到败者组场次(rule key "2")
                if (mp.getLoserTargetRound() != null) {
                    PromotionTarget loserTarget = new PromotionTarget();
                    loserTarget.setAction(StageConstants.ACTION_ADVANCE);
                    loserTarget.setTargetMatchId(matchKeyToId.get(matchKey(mp.getLoserTargetRound(), mp.getLoserTargetMatchIndex())));
                    loserTarget.setTargetSlot(mp.getLoserTargetSlot());
                    rule.put("2", loserTarget);
                }
                String json = RuleConfigParser.toJsonPromotionRule(rule);
                TMatch upd = new TMatch();
                upd.setId(matchKeyToId.get(matchKey(mp.getRound(), mp.getMatchIndex())));
                upd.setPromotionRule(json);
                matchMapper.updateById(upd);
            }
        }

        // 海选分圈:生成对阵后自动绑定圈与裁判(优先按 ruleConfig.circleRefereeIds,未配置时圈数=裁判数则 1:1,否则全部绑每圈)
        if (isAudition) {
            autoAssignCircleReferees(stage);
        }

        log.info("赛段[{}]生成对阵完成:bracketSize={}, 场次数={}", stage.getId(), plan.getBracketSize(), sorted.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    /**
     * 海选分圈后按圈绑定裁判(一圈可多裁判):
     * 优先使用 ruleConfig.circleRefereeIds(按圈顺序,每圈可多个);
     * 未配置时:圈数 = 赛段裁判数则按圈顺序 1:1,否则把赛段全部裁判绑到每个圈。
     */
    private void autoAssignCircleReferees(TStage stage) {
        // 圈集合与"每圈名额/出口按圈取人"同一口径(正式圈,不含加赛场次)
        List<TMatch> circles = auditionCircles(stage);
        if (circles.isEmpty()) {
            return;
        }
        List<Long> circleIds = circles.stream().map(TMatch::getId).toList();

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> cfg = rc != null ? rc.getCircleRefereeIds() : null;
        boolean useConfig = cfg != null && cfg.size() == circles.size();
        List<Long> stageRefereeIds = refereeStageService.getRefereeIdsByStageId(stage.getId());
        if (!useConfig && stageRefereeIds.isEmpty()) {
            // 既没有"每圈裁判"配置、也没有赛段级裁判:没有任何可用于绑定的信息,
            // 保持现状(既不删也不写)。此前是先删光再判断,这一步会把圈级绑定静默清空,
            // 且因为配置长度对不上而永远恢复不回来(开赛守卫随即报"某圈还没有裁判")。
            return;
        }
        boolean oneToOne = !useConfig && circles.size() == stageRefereeIds.size();
        // 先把"每圈绑谁"算清楚,再一次性重建,保证任何情况下都不会删了不补
        List<List<Long>> plan = new ArrayList<>(circles.size());
        for (int i = 0; i < circles.size(); i++) {
            List<Long> assigned = useConfig
                ? (cfg.get(i) == null ? List.of() : cfg.get(i))
                : (oneToOne ? List.of(stageRefereeIds.get(i)) : stageRefereeIds);
            plan.add(assigned);
        }
        // 幂等:重建前先清掉该批场次已有的圈-裁判绑定
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
            .in(TMatchReferee::getMatchId, circleIds));
        int assignedCount = 0;
        for (int i = 0; i < circles.size(); i++) {
            List<Long> assigned = plan.get(i);
            if (assigned.isEmpty()) {
                continue;
            }
            for (Long refereeId : assigned) {
                if (refereeId == null) {
                    continue;
                }
                TMatchReferee mr = new TMatchReferee();
                mr.setMatchId(circles.get(i).getId());
                mr.setRefereeId(refereeId);
                mr.setTournamentId(stage.getTournamentId());
                matchRefereeMapper.insert(mr);
                assignedCount++;
            }
        }
        log.info("赛段[{}]海选{}圈绑定裁判完成,共{}条(useConfig={})",
            stage.getId(), circles.size(), assignedCount, useConfig);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureAuditionCircles(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || plannedCircleCount(stage) < 1
            || StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }
        // 已有场次开始(进行中)后不再增删圈
        boolean anyStarted = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .ne(TMatch::getStatus, StageConstants.MATCH_PENDING)) > 0;
        if (anyStarted) {
            return;
        }
        List<TMatch> zones = auditionCircles(stage);
        int planned = plannedCircleCount(stage);
        if (zones.isEmpty()) {
            // 统一预建空圈:只建圈结构,不分配选手——落圈一律由客户端在签到时指定,
            // 因此这里不能走会按号码/名额分人的生成路径。
            addMissingAuditionCircles(stage, planned);
            log.info("海选赛段[{}]预建{}个空圈完成(不分配选手)", stageId, planned);
            return;
        }
        if (zones.size() < planned) {
            // 只允许增加圈:在末尾追加空白 ZONE match,原圈及已落圈选手保持不变
            addMissingAuditionCircles(stage, planned);
            log.info("海选赛段[{}]按配置追加空圈至{}圈完成", stageId, planned);
            return;
        }
        // 圈已建齐:本次保存可能只改了「每圈裁判」配置,按配置重新应用圈-裁判绑定。
        // 仅在配置里显式写了每圈裁判(长度与圈数一致)时才重绑——否则会走兜底规则
        // (全裁判绑每圈 / 1:1),把现场手动调好的绑定覆盖掉。
        // 场次已开始的情况在上面已提前返回,不会改动进行中的绑定。
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> cfg = rc != null ? rc.getCircleRefereeIds() : null;
        if (cfg != null && cfg.size() == zones.size()) {
            autoAssignCircleReferees(stage);
        }
    }

    /** 级联清除赛段已生成的全部场次(轮次/参赛明细/打分),用于重新生成对阵 */
    private void clearStageMatches(Long stageId) {
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (matchIds.isEmpty()) {
            return;
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery().in(TMatchReferee::getMatchId, matchIds));
        matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds));
        matchMapper.delete(Wrappers.<TMatch>lambdaQuery().in(TMatch::getId, matchIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startNextArenaMatch(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持逐场开赛");
        }
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段尚未开始,无法开始下一场对决");
        }
        long gaming = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
        if (gaming > 0) {
            throw new ServiceException("已有进行中的对决,请先完成或重启当前对决");
        }
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        if (queue.size() < 2) {
            throw new ServiceException("擂台赛至少需要 2 名参赛者,当前 {} 名", queue.size());
        }

        long count = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        TMatch m = new TMatch();
        m.setTournamentId(stage.getTournamentId());
        m.setTenantId(stage.getTenantId());
        m.setStageId(stage.getId());
        m.setName("擂台-" + (count + 1));
        m.setDisplayRow(count + 1);
        m.setDisplayCol(1L);
        m.setStatus(StageConstants.MATCH_GAMING);
        m.setMatchMode(MatchModeEnum.STANDARD.getCode());
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);

        TMatchRound round = new TMatchRound();
        round.setTournamentId(stage.getTournamentId());
        round.setTenantId(stage.getTenantId());
        round.setMatchId(m.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(round);

        insertArenaParticipant(m, queue.get(0), 1L);
        insertArenaParticipant(m, queue.get(1), 2L);

        log.info("擂台赛赛段[{}]创建第{}场对决:{} vs {}", stageId, count + 1, queue.get(0), queue.get(1));
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "match");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawArenaCompetitor(Long stageId, Long competitorId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持参赛选手弃权");
        }
        TCompetitor comp = competitorMapper.selectById(competitorId);
        if (comp == null || !Objects.equals(comp.getStageId(), stageId)) {
            throw new ServiceException("参赛选手不存在或不属于当前赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(comp.getOutcomeStatus())) {
            return; // 已弃权,幂等
        }
        outcomeWriter.writeOutcome(competitorId, OutcomeStatusEnum.WITHDRAWN.getCode());
        // 补位:同一场次内把弃权选手替换为队列下一位(不开新场)
        replaceArenaMatchParticipant(stageId, competitorId);
        log.info("擂台赛[{}]参赛选手[{}]弃权,不再参与排队", stageId, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tempWithdrawArenaCompetitor(Long stageId, Long competitorId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持临时弃权");
        }
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段未在进行中,无法临时弃权");
        }
        TCompetitor comp = competitorMapper.selectById(competitorId);
        if (comp == null || !Objects.equals(comp.getStageId(), stageId)) {
            throw new ServiceException("参赛选手不存在或不属于当前赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(comp.getOutcomeStatus())) {
            throw new ServiceException("该选手已永久弃权,无法临时弃权");
        }
        // 临时弃权 = 排到队尾:记录跳过标记(固定排在队列末尾,后续仍参与排队/对阵/排名)
        TCompetitor upd = new TCompetitor();
        upd.setId(competitorId);
        upd.setRemark(arenaQueueSupport.appendArenaSkipMark(comp.getRemark()));
        competitorMapper.updateById(upd);
        // 补位:同一场次内把临时弃权选手替换为队列下一位(不开新场)
        replaceArenaMatchParticipant(stageId, competitorId);
        log.info("擂台赛[{}]选手[{}]临时弃权,排到队尾,同场次下一位补位", stageId, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    /**
     * 弃权补位:不新建场次,把进行中对决里弃权选手的参赛方替换为队列下一位,
     * 并清空本场已提交结果(替换者从零开始)。
     * 擂主(slot1)弃权时:对手自动变擂主,队列下一位顶上来挑战;
     * 挑战者(slot2)弃权时:擂主不动,队列下一位顶上来挑战。
     * 无替补时移除对应参赛方行。
     */
    private void replaceArenaMatchParticipant(Long stageId, Long withdrawnId) {
        List<Long> gamingMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        Long matchId = null;
        Long withdrawnSlot = null;
        Long otherId = null;
        for (Long mid : gamingMatchIds) {
            List<TMatchParticipant> ps = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, mid)
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            for (TMatchParticipant p : ps) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                if (p.getCompetitorId().equals(withdrawnId)) {
                    matchId = mid;
                    withdrawnSlot = p.getDisplaySlotIndex();
                } else {
                    otherId = p.getCompetitorId();
                }
            }
            if (matchId != null) {
                break;
            }
        }
        if (matchId == null || withdrawnSlot == null) {
            return;
        }
        // 清空本场已提交结果(视同重启对决,替换者从零开始)
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId)
                .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        // 队列下一位(排除弃权者与场上对手)
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        Long otherFinal = otherId;
        Long replacement = queue.stream()
            .filter(id -> !id.equals(withdrawnId) && (otherFinal == null || !id.equals(otherFinal)))
            .findFirst().orElse(null);
        if (Long.valueOf(1L).equals(withdrawnSlot)) {
            // 擂主弃权:对手自动变擂主(slot1),队列下一位顶上来挑战(slot2)
            if (otherId == null) {
                participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 1L));
            } else {
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, otherId)
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 1L));
                if (replacement != null) {
                    participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                        .set(TMatchParticipant::getCompetitorId, replacement)
                        .eq(TMatchParticipant::getMatchId, matchId)
                        .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
                } else {
                    participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                        .eq(TMatchParticipant::getMatchId, matchId)
                        .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
                }
            }
        } else {
            // 挑战者弃权:擂主不动,队列下一位顶上来挑战(slot2)
            if (replacement != null) {
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, replacement)
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
            } else {
                participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
            }
        }
        // 双方回到待判状态
        participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
            .set(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.PENDING.getCode())
            .set(TMatchParticipant::getScoreValue, null)
            .eq(TMatchParticipant::getMatchId, matchId));
        log.info("擂台赛[{}]弃权选手[{}](slot{})由[{}]补位(同一场次,对手={})",
            stageId, withdrawnId, withdrawnSlot, replacement, otherId);
    }

    private void insertArenaParticipant(TMatch match, Long competitorId, Long slotIndex) {
        TMatchParticipant p = new TMatchParticipant();
        p.setTournamentId(match.getTournamentId());
        p.setTenantId(match.getTenantId());
        p.setMatchId(match.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(slotIndex);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
    }

    /**
     * 轮空场次自动结算:单边轮空(1 名真人)直接判胜,按淘汰赛规则填下游占位或标记晋级;
     * 双边轮空(两个空位)无胜者,仅置为已结算。返回本次结算的场次数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settleByeMatches(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || !StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            return 0;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .in(TMatch::getStatus, StageConstants.MATCH_PENDING, StageConstants.MATCH_GAMING));
        int settled = 0;
        for (TMatch m : matches) {
            if (settleByeMatch(m)) {
                settled++;
            }
        }
        if (settled > 0) {
            log.info("赛段[{}]轮空场次自动结算 {} 场", stageId, settled);
        }
        return settled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleByeMatch(Long matchId) {
        if (matchId == null) {
            return false;
        }
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            return false;
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage == null || !StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            return false;
        }
        return settleByeMatch(match);
    }

    /** 结算单个轮空场次:单边轮空(1 名真人)判胜并填下游/标晋级;双边轮空仅置已结算。非轮空返回 false。 */
    private boolean settleByeMatch(TMatch m) {
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, m.getId())
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        List<TMatchParticipant> real = parts.stream()
            .filter(p -> p.getCompetitorId() != null)
            .toList();
        if (real.size() >= 2) {
            return false; // 正常对决,不处理
        }
        // 空场次能否按轮空结算,取决于"还会不会有人补进来":本赛段内所有会向本场送人的
        // 上游场次都已结算(胜/败者该来的都来了、该空的就永远空),才能结算为空轮空。
        // 此前用 displayCol > 1 粗判,导致"半决赛双方都是轮空"时季军赛永远等不到人:
        // 既开不了(报"暂无参赛方")也结算不了,整个赛段卡死在"仍有 1 场未结算"。
        if (real.isEmpty() && hasUnsettledUpstream(m)) {
            return false;
        }
        if (real.size() == 1) {
            TMatchParticipant winner = real.get(0);
            TMatchParticipant upd = new TMatchParticipant();
            upd.setOutcomeStatus(MatchOutcomeEnum.WIN.getCode());
            upd.setRankInMatch(1L);
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .eq(TMatchParticipant::getCompetitorId, winner.getCompetitorId()));
            resolveKnockoutByeWinner(m, winner.getCompetitorId());
        }
        settlementSupport.markMatchSettled(m);
        return true;
    }

    /**
     * 本赛段内是否还有会向 {@code match} 填入参赛方的未结算场次(按 promotion_rule 反查)。
     *
     * <p>跨赛段的晋级走名单装配,开赛时参赛行已物化完毕(上一赛段必须 SETTLED 才能开赛),
     * 因此只在本赛段内反查即可。</p>
     */
    private boolean hasUnsettledUpstream(TMatch match) {
        List<TMatch> sameStage = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, match.getStageId())
            .select(TMatch::getId, TMatch::getStatus, TMatch::getPromotionRule));
        for (TMatch m : sameStage) {
            if (Objects.equals(m.getId(), match.getId())
                || StageConstants.MATCH_SETTLED.equals(m.getStatus())) {
                continue;
            }
            Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(m.getPromotionRule());
            for (PromotionTarget target : rule.values()) {
                if (target != null && Objects.equals(target.getTargetMatchId(), match.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 轮空胜者去向:填下游场次占位;finalMatch 则标记晋级下一赛段 */
    private void resolveKnockoutByeWinner(TMatch match, Long winnerCompetitorId) {
        // 与正常结算共用同一套去向逻辑(决赛标晋级 / 填下游占位并补插缺失占位行)
        downstreamRouter.routeWinner(match, winnerCompetitorId);
    }

    @Override
    public ArenaOverviewVo getArenaOverview(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持擂台总览");
        }
        ArenaOverviewVo vo = new ArenaOverviewVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStatus(stage.getStatus());

        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        Map<Long, TCompetitor> compMap = comps.stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c));
        Map<Long, Integer> points = arenaQueueSupport.arenaPoints(stageId);
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        Map<Long, String> avatars = loadAvatarMap(new ArrayList<>(compMap.keySet()));

        List<ArenaOverviewVo.CompetitorInfo> queueList = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            queueList.add(toArenaCompetitor(queue.get(i), compMap, avatars, points, i + 1));
        }
        vo.setQueue(queueList);

        List<TMatch> gaming = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
            .orderByAsc(TMatch::getId));
        if (!gaming.isEmpty()) {
            TMatch cur = gaming.get(0);
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, cur.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            ArenaOverviewVo.MatchInfo mi = new ArenaOverviewVo.MatchInfo();
            mi.setMatchId(cur.getId());
            mi.setMatchName(cur.getName());
            if (!parts.isEmpty() && parts.get(0).getCompetitorId() != null) {
                mi.setDefender(toArenaCompetitor(parts.get(0).getCompetitorId(), compMap, avatars, points, 1));
            }
            if (parts.size() > 1 && parts.get(1).getCompetitorId() != null) {
                mi.setChallenger(toArenaCompetitor(parts.get(1).getCompetitorId(), compMap, avatars, points, 2));
            }
            vo.setCurrentMatch(mi);
        }

        long settled = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        vo.setBattleCount(settled);
        return vo;
    }

    private ArenaOverviewVo.CompetitorInfo toArenaCompetitor(Long competitorId,
                                                             Map<Long, TCompetitor> compMap,
                                                             Map<Long, String> avatars,
                                                             Map<Long, Integer> points,
                                                             int queueIndex) {
        TCompetitor c = compMap.get(competitorId);
        ArenaOverviewVo.CompetitorInfo ci = new ArenaOverviewVo.CompetitorInfo();
        ci.setCompetitorId(competitorId);
        ci.setName(c != null ? c.getName() : null);
        ci.setNumber(c != null ? c.getNumber() : null);
        ci.setAvatar(avatars.get(competitorId));
        ci.setPoints(points.getOrDefault(competitorId, 0));
        ci.setQueueIndex(queueIndex);
        return ci;
    }

    /** 参赛方首张照片:competitor → member → player.avatar */
    private Map<Long, String> loadAvatarMap(List<Long> compIds) {
        Map<Long, String> map = new HashMap<>();
        if (compIds == null || compIds.isEmpty()) {
            return map;
        }
        List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .in(TCompetitorMember::getCompetitorId, compIds));
        if (members.isEmpty()) {
            return map;
        }
        List<Long> playerIds = members.stream()
            .map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (playerIds.isEmpty()) {
            return map;
        }
        Map<Long, String> avatars = playerMapper.selectList(Wrappers.<TPlayer>lambdaQuery()
                .in(TPlayer::getId, playerIds))
            .stream()
            .filter(p -> StringUtils.isNotBlank(p.getAvatar()))
            .collect(Collectors.toMap(TPlayer::getId, TPlayer::getAvatar, (a, b) -> a));
        for (TCompetitorMember mem : members) {
            String av = avatars.get(mem.getPlayerId());
            if (StringUtils.isNotBlank(av)) {
                map.putIfAbsent(mem.getCompetitorId(), av);
            }
        }
        return map;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startStage(Long stageId) {
        TStage stage = mustGetStage(stageId);
        assertCanStart(stage);
        // 一键开赛:无对阵时自动初始化(如未初始化)并生成对阵,淘汰赛/小组赛/海选均适用
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        boolean isArena = StageModeEnum.ARENA.getCode().equals(stage.getStageMode());
        // 自由对抗:对手由线下抽签/指认,场次全部由导播台手动添加,开赛不生成任何对阵
        boolean isFreeMatch = StageModeEnum.FREE_MATCH.getCode().equals(stage.getStageMode());
        if (exist == 0) {
            long entrants = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode()));
            if (entrants == 0) {
                // 本赛段没有参赛方(上一赛段晋级人数不足、甚至一个都没晋级):
                // 不初始化也不生成对阵,直接进入进行中,由导播台完成赛段即可。
                // 否则会卡在"赛段无可初始化的参赛方",后面的赛段整条链都走不下去。
                log.warn("赛段[{}]没有参赛方,跳过初始化与生成对阵,直接进入进行中", stageId);
            } else {
                if (!Long.valueOf(1L).equals(stage.getIsInitialized())) {
                    InitializeStageBo initBo = new InitializeStageBo();
                    initBo.setStageId(stageId);
                    initialize(initBo);
                    // 初始化后重新读取赛段(状态/isInitialized 已更新)
                    stage = mustGetStage(stageId);
                }
                if (!isArena && !isFreeMatch) {
                    GenerateMatchesBo gm = new GenerateMatchesBo();
                    gm.setStageId(stageId);
                    generateMatches(gm);
                }
            }
        }
        // 轮空场次不在此自动结算:保持 PENDING,由导播台逐场点「开始」时再自动结束(见 settleByeMatch),
        // 保证淘汰赛的每一场(含轮空)都经过导播台确认
        // 圈/对阵已预建(exist>0)时也要在开赛这一刻锁定名单:否则预建圈的赛段开赛后
        // isInitialized 仍是 0,种子位还能被继续调整。空赛段没有名单可锁,保持未初始化。
        if (exist > 0 && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            long pendingComps = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            if (pendingComps > 0) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stageId);
                initialize(initBo);
                stage = mustGetStage(stageId);
            }
        }
        // 海选落圈守卫:圈位由客户端在签到时指定,开赛前必须人人已落圈(见 assertAuditionAllAttached)
        assertAuditionAllAttached(stageId);
        // 海选裁判守卫:每个圈都要有裁判,否则开赛后没人能判、赛段既结算不了也结束不了
        assertAuditionCirclesHaveReferees(stageId);
        ensureStageGaming(stageId);

        // 擂台赛:开赛后自动创建并开始第一场对决(队首擂主 vs 队次挑战者)
        if (isArena) {
            startNextArenaMatch(stageId);
            return;
        }

        // 淘汰赛:开始赛段仅完成生成与开赛,场次全部保持待开始,由导播台逐场点「开始」开始(避免自动开始第一场)。
        // 海选等其他赛制:场次一并进入 GAMING,裁判可直接开评。
        boolean singleActive = StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode());
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
        for (int i = 0; i < matches.size(); i++) {
            // 轮空已自动结算的场次保持 SETTLED,不重置回 PENDING
            if (StageConstants.MATCH_SETTLED.equals(matches.get(i).getStatus())) {
                continue;
            }
            String targetStatus = singleActive
                ? StageConstants.MATCH_PENDING
                : StageConstants.MATCH_GAMING;
            // 场次与轮次成套推进
            matchStateWriter.setStatus(matches.get(i).getId(), targetStatus);
        }
    }

    /**
     * 海选开赛守卫:所有未退赛的参赛方都必须已落入某个圈场次。
     *
     * <p>落圈由客户端(签到页)指定,后端不再自动分配。若有人没落圈就开赛,
     * 他不会参与任何场次,结算时静默消失——所以这里宁可拦住并列出具体名单。</p>
     */
    private void assertAuditionAllAttached(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || !StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            return;
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode()));
        if (comps.isEmpty()) {
            return;
        }
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        Set<Long> attached = matchIds.isEmpty() ? Set.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .select(TMatchParticipant::getCompetitorId))
                .stream().map(TMatchParticipant::getCompetitorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missed = comps.stream()
            .filter(c -> !attached.contains(c.getId()))
            .map(c -> c.getNumber() == null ? c.getName() : c.getName() + "(" + c.getNumber() + "号)")
            .toList();
        if (!missed.isEmpty()) {
            throw new ServiceException("海选有 {} 名参赛者尚未落圈,无法开始:{};请先为其指定圈子"
                + "(签到页选圈,或调用「参赛方落圈」接口)",
                missed.size(), String.join("、", missed));
        }
    }

    /**
     * 海选开赛守卫:每个圈都必须至少有一名裁判。
     *
     * <p>海选是<b>按圈判</b>的——裁判端只显示自己绑到圈上的那场({@code t_match_referee})。
     * 圈上没有裁判时谁也打不了分,赛段既结算不了也结束不了,现场只能删赛事重建;
     * 而"赛段级裁判"({@code t_referee_stage})只表示谁参与本赛段,不会自动落到圈上。
     * 所以开赛前拦住,并列出缺裁判的圈。</p>
     */
    private void assertAuditionCirclesHaveReferees(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || !StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            return;
        }
        List<TMatch> circles = auditionCircles(stage);
        if (circles.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < circles.size(); i++) {
            TMatch circle = circles.get(i);
            long refs = matchRefereeMapper.selectCount(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, circle.getId()));
            if (refs == 0) {
                missing.add(circle.getName() != null ? circle.getName() : ("第" + (i + 1) + "圈"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("海选有 {} 个圈还没有裁判,无法开始:{};"
                + "请给每圈指定裁判(赛段流程→海选配置→每圈裁判)",
                missing.size(), String.join("、", missing));
        }
    }

    /**
     * 裁判配置锁定校验:赛段一旦开始(非 DRAFT)就不能再改裁判。
     *
     * <p>配合开赛守卫(每圈必须有裁判),开始后加减裁判既无必要也会出问题:
     * 海选按累加聚合,中途加减裁判会让同场选手由不同数量的裁判打分、分数不可比,
     * 且减裁判不会撤销其已提交的分。</p>
     */
    @Override
    public void assertRefereesEditable(Long stageId) {
        if (stageId == null) {
            return;
        }
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            return;
        }
        throw new ServiceException("赛段[{}]已开始,裁判配置已锁定:如需调整请先重置赛段",
            stage.getName());
    }

    /**
     * 开赛前的全部守卫:状态可开、海选圈已配置、上一赛段已结束、名单已就绪。
     * 任一不满足即抛 ServiceException;通过后调用方才有权生成对阵并置 GAMING。
     */
    private void assertCanStart(TStage stage) {
        // 未开始只有 DRAFT 一个状态:一键开始会自动初始化、生成对阵,再置 GAMING
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)状态的赛段可开始,当前: {}", stage.getStatus());
        }
        // 海选圈默认为空:必须先配置至少一圈(人数/裁判/去向)才能开始
        if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            && plannedCircleCount(stage) < 1) {
            throw new ServiceException("海选尚未配置圈,请先在赛段配置中新增至少一圈(人数/裁判/去向)");
        }
        // 流程规范:上一赛段必须已结束(SETTLED),否则不允许开始本赛段。
        // 前驱由 next 链推导(见 StageChain);prev 列只是展示字段,与链不一致时
        // 按链修正(两个方向),不当作致命错误拦下开赛——拦截一件只影响展示的
        // 不一致,代价是主办方开不了赛。
        TStage prev = stageService.resolvePrevStage(stage);
        stageChain.reconcilePrevColumn(stage);
        if (prev == null) {
            // 链表头(入口赛段)没有上游,名单守卫由名单服务按"无内部来源组"自动放行
            return;
        }
        if (!StageConstants.STAGE_SETTLED.equals(prev.getStatus())) {
            throw new ServiceException("上一赛段[{}]尚未结束,无法开始本赛段", prev.getName());
        }
        // 名单守卫(规则+覆盖+快照模型):语义见 ITStageRosterService#assertStageStartable
        rosterService.assertStageStartable(stage.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureStageGaming(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            // 已是 GAMING/SETTLED:幂等空操作,不重复落库与广播
            return;
        }
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(upd);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId) {
        appendStageCompetitor(stageId, competitorId, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        appendStageCompetitor(stageId, competitorId, targetMatchId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId, Integer zoneIndex) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = mustGetStage(stageId);
        // 海选/排名赛均为逐选手轮次:签到/补签到选手直接挂入未结算圈场次,可被裁判打分。
        // 已生成对阵但尚未开赛(PENDING)时同样挂入,否则补签到选手会从打分中"消失"
        // (不参与任何场次,结算后无晋级/淘汰结果,且无任何提示)。
        boolean perCompetitor = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        if (!perCompetitor) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }

        // 海选圈必须先建好(ensure-circle-slots / generate-matches),签到只负责把人落进已有的圈。
        // 这里不再按配置补建圈场次——「没有圈也能签到」会让选手静默不参与打分与结算。
        boolean auditionSplit = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode());
        if (auditionSplit && auditionCircles(stage).isEmpty()) {
            throw new ServiceException("海选赛段尚未建立圈场次,请先创建圈后再签到");
        }

        // 逐选手模式(AUDITION/RANK)补签到窗口:进行中或规划中均可挂入;
        // 已生成对阵但尚未开赛(DRAFT)同样允许挂入,否则迟到者会从打分中"消失"。
        // 非逐选手赛制(淘汰/小组/擂台)仍保持原语义,不在生成后追加参赛方。
        boolean attachable = StageConstants.STAGE_GAMING.equals(stage.getStatus())
            || (StageConstants.STAGE_DRAFT.equals(stage.getStatus())
                && (auditionSplit || perCompetitor));
        if (!attachable) {
            return;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            // 尚无场次:后续生成对阵(自动补救)时会纳入该参赛方,无需处理
            return;
        }
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        long existed = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
        if (existed > 0) {
            return;
        }
        // 仅可挂入未结算的正式圈场次(加赛只允许同分选手参与,不追加新人)
        List<TMatch> candidates = matches.stream()
            .filter(m -> !StageConstants.MATCH_SETTLED.equals(m.getStatus()))
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
        if (candidates.isEmpty()) {
            if (auditionSplit) {
                throw new ServiceException("海选没有可落圈的圈场次(圈已结算或正在进行加赛),无法签到");
            }
            return;
        }
        TMatch target;
        if (targetMatchId != null) {
            // 目标圈须为未结算的正式圈(加赛场次不可追加新人)
            target = candidates.stream()
                .filter(m -> m.getId().equals(targetMatchId))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                    "目标圈场次不存在、已结算或为加赛场次,无法加入;请选择其他圈"));
        } else if (zoneIndex != null) {
            // 客户端只给了圈序号(尚未拿到场次ID时):第 k 圈 = 本赛段第 k 个正式圈场次
            // (candidates 已按 displayRow、id 升序,与 auditionCircles 同一顺序)
            if (zoneIndex <= 0 || candidates.size() < zoneIndex) {
                throw new ServiceException("目标圈[第{}圈]不存在或不可用", zoneIndex);
            }
            target = candidates.get(zoneIndex - 1);
        } else {
            // 落圈一律由客户端决定:后端不再按号码/名额择优推导,
            // 否则同一批号码会因「先建圈后签到 / 签完再生成」等调用顺序不同而落到不同的圈。
            throw new ServiceException("请指定落圈:补签到必须传入目标圈场次ID或圈序号");
        }
        appendParticipantWithRound(target, competitorId);
        log.info("海选/排名赛段[{}]补签到:参赛方[{}]挂入场次[{}]", stageId, competitorId, target.getId());
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void relocateCheckInCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            && !StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法修改签到结果");
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            // 尚未生成对阵:号码更新由后续生成对阵统一纳入
            return;
        }
        TMatch source = null;
        for (TMatch m : matches) {
            long cnt = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .eq(TMatchParticipant::getCompetitorId, competitorId));
            if (cnt > 0) {
                source = m;
                break;
            }
        }
        if (source == null) {
            // 未挂入场次(异常数据兜底):直接按新号码/目标圈补挂,避免新号码不参与场次
            appendStageCompetitor(stageId, competitorId, targetMatchId);
            return;
        }

        TMatch target;
        if (targetMatchId != null) {
            target = matches.stream().filter(m -> m.getId().equals(targetMatchId))
                .findFirst().orElse(null);
            if (target == null) {
                throw new ServiceException("目标圈场次不存在");
            }
            if (StageConstants.MATCH_SETTLED.equals(target.getStatus())
                || settlementSupport.isTiebreaker(target)) {
                throw new ServiceException("目标圈场次已结算或为加赛场次,无法改入");
            }
        } else {
            // 未指定目标圈:保持原圈,仅在圈内按新号码重排。
            // 需要换圈时由客户端显式传入 targetMatchId——后端不再按号码推导圈位。
            target = source;
        }

        removeParticipantWithRound(source, competitorId);
        appendParticipantWithRound(target, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
        log.info("海选/排名赛段[{}]编辑签到:参赛方[{}]从场次[{}]改入场次[{}]",
            stageId, competitorId, source.getId(), target.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCheckInCompetitor(Long stageId, Long competitorId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            && !StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法解除签到");
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        boolean removed = false;
        for (TMatch m : matches) {
            long cnt = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .eq(TMatchParticipant::getCompetitorId, competitorId));
            if (cnt > 0) {
                removeParticipantWithRound(m, competitorId);
                removed = true;
            }
        }
        if (removed) {
            tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
            log.info("海选/排名赛段[{}]解除签到:参赛方[{}]已移出圈场次", stageId, competitorId);
        }
    }

    /**
     * 从场次移除参赛方及其独立轮次:已有打分记录时禁止移除;
     * 移除后按号码顺序重排本场 participant 槽位与轮次序号。
     */
    private void removeParticipantWithRound(TMatch match, Long competitorId) {
        TMatchRound round = matchRoundMapper.selectOne(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())
            .eq(TMatchRound::getCompetitorId, competitorId)
            .last("limit 1"));
        if (round != null) {
            long scored = roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId()));
            if (scored > 0) {
                throw new ServiceException(
                    "该选手在「{}」已有打分记录,无法修改/解除签到,请先处理该场次成绩", match.getName());
            }
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId()));
            matchRoundMapper.deleteById(round.getId());
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, competitorId));
        renumberMatchParticipants(match.getId());
    }

    /**
     * 按号码顺序重建场次内的展示位与轮次序号(移除/改号后保持 1..n 连续)。
     */
    private void renumberMatchParticipants(Long matchId) {
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        if (parts.isEmpty()) {
            return;
        }
        Map<Long, TCompetitor> compById = competitorMapper.selectByIds(parts.stream()
                .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        parts.sort(Comparator
            .comparingInt((TMatchParticipant p) -> {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                return c == null ? Integer.MAX_VALUE : parseCompetitorNumber(c.getNumber());
            })
            .thenComparingLong(TMatchParticipant::getId));

        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, matchId)
            .orderByAsc(TMatchRound::getRoundSequence));
        Map<Long, List<TMatchRound>> roundsByComp = rounds.stream()
            .filter(r -> r.getCompetitorId() != null)
            .collect(Collectors.groupingBy(TMatchRound::getCompetitorId));

        for (int i = 0; i < parts.size(); i++) {
            TMatchParticipant p = parts.get(i);
            long targetSlot = i + 1L;
            // 只写真的变了的那几行:补签到常见的是"插到末尾",此时前面几十号人
            // 的槽位一字未动,不该跟着写一遍(36 人圈 = 36 条无谓 UPDATE)
            if (p.getDisplaySlotIndex() == null || p.getDisplaySlotIndex() != targetSlot) {
                TMatchParticipant slotUpd = new TMatchParticipant();
                slotUpd.setId(p.getId());
                slotUpd.setDisplaySlotIndex(targetSlot);
                participantMapper.updateById(slotUpd);
            }
            List<TMatchRound> own = roundsByComp.getOrDefault(p.getCompetitorId(), List.of());
            if (!own.isEmpty()) {
                TMatchRound round = own.get(0);
                if (round.getRoundSequence() == null || round.getRoundSequence() != targetSlot) {
                    TMatchRound rUpd = new TMatchRound();
                    rUpd.setId(round.getId());
                    rUpd.setRoundSequence(targetSlot);
                    matchRoundMapper.updateById(rUpd);
                }
            }
        }
    }

    /** 持久化海选分圈方式(随机 true / 按号 false),保留 rule_config 中其余自定义字段 */
    private void persistCircleSplitMode(TStage stage, boolean randomSplit) {
        if (StringUtils.isBlank(stage.getRuleConfig())) {
            return;
        }
        try {
            tools.jackson.databind.ObjectMapper mapper = SnowflakeJson.mapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(stage.getRuleConfig(), Map.class);
            raw.put("randomSplit", randomSplit);
            stage.setRuleConfig(mapper.writeValueAsString(raw));
            stageMapper.updateById(stage);
        } catch (Exception e) {
            log.warn("海选赛段[{}]持久化分圈方式失败: {}", stage.getId(), e.getMessage());
        }
    }

    /** 海选计划圈数(ruleConfig.circles;默认为空 0,必须显式新增圈) */
    private int plannedCircleCount(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        return (rc != null && rc.getCircles() != null) ? Math.max(0, rc.getCircles()) : 0;
    }

    /**
     * 海选圈场次的<b>唯一口径</b>:本赛段除同分加赛外的全部正式场次,按展示序。
     *
     * <p>圈就是正式场次,单圈/多圈不分开判断——圈序号即本列表下标 +1,分区名恒为
     * {@code ZONE-k}(单圈即 {@code ZONE-1})。此前"多圈只认 ZONE-* 场次、单圈另取一个
     * CENTER 名字"的分叉,会让单圈场次在加圈后掉出圈集合:名额算在它身上、裁判绑在它身上,
     * 但"按圈取人"和"补圈"都不认它(表现为加一圈后新圈 0 名额、圈级裁判绑定被清空)。</p>
     */
    private List<TMatch> auditionCircles(TStage stage) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId))
            .stream()
            // 同分加赛复用原圈 displayZone,不计入"圈场次"
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
    }

    /**
     * 海选增加圈:只追加缺失的空白 ZONE match(原有圈、已落圈选手、轮次均不动)。
     * 新圈无选手/无轮次,签到落圈或补签时自动写入;裁判绑定按配置整体重绑。
     */
    private void addMissingAuditionCircles(TStage stage, int planned) {
        // 兜底:任一原始圈场次已开始就不再补圈(调用方应已校验)
        boolean anyStarted = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_PENDING)) > 0;
        if (anyStarted) {
            log.warn("海选赛段[{}]已有场次开始,跳过补圈(配置{}圈,当前{}圈)", stage.getId(),
                planned, auditionCircles(stage).size());
            return;
        }
        List<TMatch> zones = auditionCircles(stage);
        if (zones.size() >= planned) {
            return;
        }
        String matchMode = zones.stream()
            .map(TMatch::getMatchMode)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(MatchModeEnum.VOTING.getCode());
        for (int c = zones.size() + 1; c <= planned; c++) {
            TMatch m = new TMatch();
            m.setTournamentId(stage.getTournamentId());
            m.setTenantId(stage.getTenantId());
            m.setStageId(stage.getId());
            // 与生成器同一口径:第 k 个圈恒为 ZONE-k(单圈即 ZONE-1)
            m.setName(StageFlowSupport.circleName(planned, c));
            m.setDisplayZone(StageFlowSupport.circleZone(c));
            m.setDisplayRow((long) (c - 1));
            m.setDisplayCol(1L);
            m.setStatus(StageConstants.MATCH_PENDING);
            m.setMatchMode(matchMode);
            m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
            matchMapper.insert(m);
            log.info("海选赛段[{}]追加空白第{}圈(matchId={})", stage.getId(), c, m.getId());
        }
        // 按配置刷新圈-裁判绑定(新增圈一并绑定,已有圈幂等重绑)
        autoAssignCircleReferees(stage);
    }

    /** 加赛深度 -> sheet 名:1=二海,2=三海,3=四海,4=五海(加赛上限内最多四级) */
    private String tiebreakerSheetName(int depth) {
        return switch (depth) {
            case 1 -> "二海";
            case 2 -> "三海";
            case 3 -> "四海";
            case 4 -> "五海";
            default -> "加赛" + depth;
        };
    }

    /** 海选加赛参赛方结果文本 */
    private String auditionResultText(String status) {
        if (status == null) {
            return "";
        }
        if ("ADVANCE".equals(status)) return "晋级";
        if ("ELIMINATED".equals(status)) return "淘汰";
        if ("PENDING".equals(status)) return "进行中";
        if ("WITHDRAWN".equals(status)) return "退赛";
        return status;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int setSeedOrder(SeedOrderBo bo) {
        TStage stage = mustGetStage(bo.getStageId());
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,种子已锁定;无法再按抽签结果调整顺序");
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法调整种子顺序");
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()));
        Set<Long> stageIds = comps.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        List<Long> order = bo.getCompetitorIds();
        if (order.size() != stageIds.size()) {
            throw new ServiceException("参赛方数量不一致:赛段共{}人,提交顺序{}人", stageIds.size(), order.size());
        }
        for (Long cid : order) {
            if (cid == null || !stageIds.contains(cid)) {
                throw new ServiceException("参赛方[{}]不属于当前赛段", cid);
            }
        }
        // 同上:抽签结果批量落库
        List<Map<String, Object>> seedItems = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", order.get(i));
            item.put("seedRank", (long) (i + 1));
            seedItems.add(item);
        }
        if (!seedItems.isEmpty()) {
            competitorMapper.batchUpdateSeedRank(seedItems);
        }
        log.info("赛段[{}]按外部抽签结果设定{}个参赛方种子顺序", stage.getId(), order.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return order.size();
    }

    /** 追加参赛方:仅新增 participant,复用场次已有轮次(淘汰赛/小组赛一个场次一个通用轮次) */
    private void appendParticipant(TMatch target, Long competitorId) {
        long nextSlot = nextSlotIndex(target);
        TMatchParticipant p = new TMatchParticipant();
        p.setTenantId(target.getTenantId());
        p.setTournamentId(target.getTournamentId());
        p.setMatchId(target.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(nextSlot);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
        log.info("参赛方[{}]追加到场次[{}](slot={})", competitorId, target.getId(), nextSlot);
    }

    /**
     * 追加参赛方并新建轮次(海选补签到:每个参赛方一个独立轮次,裁判逐选手打分)。
     * 海选/排名赛按号码数值排序上场:补签选手按其号码插入对应位置,
     * 插入点之后的参赛方(slot)与轮次(round)统一顺延 +1,避免新选手被追加到队尾导致号码排序错位。
     */
    private void appendParticipantWithRound(TMatch target, Long competitorId) {
        TCompetitor newcomer = competitorId == null ? null : competitorMapper.selectById(competitorId);
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, target.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        Map<Long, TCompetitor> compById = parts.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(parts.stream()
                    .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        // 插入位置:号码数值小于新选手的参赛方数量(同号排在已有同号之后)
        int insertIdx = 0;
        if (newcomer != null) {
            for (TMatchParticipant p : parts) {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                if (c == null || parseCompetitorNumber(c.getNumber()) < parseCompetitorNumber(newcomer.getNumber())) {
                    insertIdx++;
                }
            }
        } else {
            insertIdx = parts.size();
        }
        long newSlot = insertIdx + 1L;
        long newRound = insertIdx + 1L;

        // 插入点及之后的参赛方 slot 顺延 +1
        if (newcomer != null) {
            for (TMatchParticipant p : parts) {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                if (c != null
                    && parseCompetitorNumber(c.getNumber()) >= parseCompetitorNumber(newcomer.getNumber())) {
                    TMatchParticipant upd = new TMatchParticipant();
                    upd.setId(p.getId());
                    upd.setDisplaySlotIndex((p.getDisplaySlotIndex() == null ? 0L : p.getDisplaySlotIndex()) + 1L);
                    participantMapper.updateById(upd);
                }
            }
            // 插入点及之后的轮次 sequence 顺延 +1(每个参赛方一个独立轮次,按 competitorId 对齐)
            Set<Long> shiftRoundCompetitorIds = parts.stream()
                .filter(p -> {
                    TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                    return c != null
                        && parseCompetitorNumber(c.getNumber()) >= parseCompetitorNumber(newcomer.getNumber());
                })
                .map(TMatchParticipant::getCompetitorId)
                .collect(Collectors.toSet());
            List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, target.getId())
                .orderByAsc(TMatchRound::getRoundSequence));
            for (TMatchRound r : rounds) {
                if (r.getCompetitorId() != null && shiftRoundCompetitorIds.contains(r.getCompetitorId())) {
                    TMatchRound upd = new TMatchRound();
                    upd.setId(r.getId());
                    upd.setRoundSequence((r.getRoundSequence() == null ? 0L : r.getRoundSequence()) + 1L);
                    matchRoundMapper.updateById(upd);
                }
            }
        }

        TMatchParticipant p = new TMatchParticipant();
        p.setTenantId(target.getTenantId());
        p.setTournamentId(target.getTournamentId());
        p.setMatchId(target.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(newSlot);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);

        // 每个参赛方一个独立轮次,裁判逐选手打分
        TMatchRound round = new TMatchRound();
        round.setTenantId(target.getTenantId());
        round.setTournamentId(target.getTournamentId());
        round.setMatchId(target.getId());
        round.setRoundSequence(newRound);
        round.setCompetitorId(competitorId);
        round.setStatus(target.getStatus());
        matchRoundMapper.insert(round);

        log.info("参赛方[{}]挂入场次[{}](slot={},round={})", competitorId, target.getId(), newSlot, newRound);
    }

    /** 场次内下一可用展示位 */
    private long nextSlotIndex(TMatch target) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, target.getId())
                .select(TMatchParticipant::getDisplaySlotIndex))
            .stream().mapToLong(p -> p.getDisplaySlotIndex() == null ? 0L : p.getDisplaySlotIndex())
            .max().orElse(0L) + 1L;
    }

    /** 本赛段名单的来源里是否有海选/排名赛(决定默认是否头尾交叉配对) */
    private boolean seedsFromRanking(TStage stage) {
        if (stage == null) {
            return false;
        }
        return PairingModeResolver.seedsFromRanking(
            StageRosterGroupCodec.parse(stage.getRosterConfigJson()),
            stageMapper::selectById);
    }

    /** 生成对阵前把空配对方式按统一口径补齐,保证与中间态/大屏一致 */
    private void normalizePairingMode(TStage stage, RuleConfigHolder rc) {
        if (rc == null || rc.getKnockout() == null) {
            return;
        }
        if (org.apache.commons.lang3.StringUtils.isNotBlank(rc.getKnockout().getPairingMode())) {
            return;
        }
        // 上一赛段走链遍历(next 为事实源),决定配对方式时同样以链为准
        TStage prev = stageService.resolvePrevStage(stage);
        rc.getKnockout().setPairingMode(PairingModeResolver.resolve(
            null, seedsFromRanking(stage), PairingModeResolver.prevIsRanking(prev)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StageCompleteVo completeStage(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("仅 GAMING 状态的赛段可完成,当前: {}", stage.getStatus());
        }
        // 按赛制选结算策略(见 settle 包):每种赛制的结算规则独立成类,
        // 这里只负责"选策略 → 置状态 → 广播"。
        StageSettleOutcome outcome = settlerRegistry.of(stage.getStageMode()).settle(stage);
        if (!outcome.closable()) {
            // 结算产生了后续工作(二海加赛、仍有场次未结算…):赛段保持进行中。
            // 「还不能结束」统一用返回值表达,不再与异常混用——导播台只需看 message。
            log.info("赛段[{}]暂不能结束:{}", stageId, outcome.reason());
            // 同分加赛单独标记:前端据此弹「需要加赛」,其余原因仍走普通提示。
            return outcome.tiebreaker()
                ? StageCompleteVo.pendingTiebreaker(outcome.reason())
                : StageCompleteVo.pending(outcome.reason());
        }
        stage.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(stage);
        // 名单就绪度由源结算状态推导,结算完成无需推进任何状态;
        // 下游开赛守卫与 apply 都会现场按源状态计算。
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        // 不再由后端自动确认晋级:完成赛段仅产出晋级预排;是否跳过中间态由 MC 导播台
        // 在开始下一赛段时弹窗确认后调用「确认晋级」接口决定(见 isAutoConfirmAdvancement)
        return StageCompleteVo.settled();
    }

    /**
     * 赛事级配置:是否开启「跳过中间态确认阶段」(themeConfig.autoConfirmAdvancement,默认开启)。
     * 开启后 MC 导播台在开始赛段时弹窗确认,调用确认晋级接口跳过中间态直接开始。
     */
    @Override
    public boolean isAutoConfirmAdvancement(Long tournamentId) {
        if (tournamentId == null) {
            return false;
        }
        TTournament t = tournamentMapper.selectById(tournamentId);
        if (t == null || StringUtils.isBlank(t.getThemeConfig())) {
            return true;
        }
        try {
            return cn.hutool.json.JSONUtil.parseObj(t.getThemeConfig())
                .getBool("autoConfirmAdvancement", true);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 导出海选结果 Excel:
     * <ul>
     *   <li>"海选成绩" sheet:号码 / 选手名 / 各裁判分数 / 总分 / 排名,总分只统计原始海选场;</li>
     *   <li>二海/三海/… sheet:按加赛深度各占一张(号码 / 选手名 / 各裁判分数 / 总分 / 结果),
     *       加赛分数仅用于同分者决出晋级顺序,不进入主表总分。</li>
     * </ul>
     * 数据统一来自 {@link #queryAuditionResult(Long)},此处不再重复聚合。
     */
    @Override
    public void exportAuditionResult(Long stageId, jakarta.servlet.http.HttpServletResponse response) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅海选赛赛段支持导出海选结果");
        }
        AuditionResultVo result = queryAuditionResult(stageId);
        // 裁判列顺序:赛事全部裁判按 id 升序(未打分的裁判该列留空)
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));
        // 多圈导出:主表与加赛表都带"圈"列(显示该圈裁判名,无裁判回退第N圈)
        Map<String, String> zoneLabels = buildAuditionZoneLabelMap(stage, referees);
        boolean multiCircle = zoneLabels.size() > 1;

        // 主表头:号码 | 选手名 | [圈] | 裁判1..n | 总分 | 排名
        List<List<String>> head = new ArrayList<>();
        head.add(List.of("号码"));
        head.add(List.of("选手名"));
        if (multiCircle) {
            head.add(List.of("圈"));
        }
        for (TReferee r : referees) {
            head.add(List.of(StringUtils.defaultString(r.getName(), "裁判" + r.getId())));
        }
        head.add(List.of("总分"));
        head.add(List.of("排名"));
        // 排序口径与名单页「分数排名」一致:原始分降序 → 加赛(二海/三海)分降序 → 号码牌升序;
        // 多圈时先按圈分组,与「排名」列的分圈名次保持一致
        Map<Long, BigDecimal> tiebreakScore = auditionTiebreakScoreMap(result.getTiebreakers());
        List<String> zoneOrder = auditionCircles(stage).stream()
            .map(TMatch::getDisplayZone).filter(Objects::nonNull).distinct().toList();
        List<AuditionResultVo.CompetitorItem> mainItems = new ArrayList<>(result.getCompetitors());
        mainItems.sort(auditionExportComparator(zoneOrder, tiebreakScore));
        List<List<Object>> rows = new ArrayList<>();
        for (AuditionResultVo.CompetitorItem c : mainItems) {
            rows.add(auditionExportRow(c, referees, true, zoneLabels, multiCircle));
        }

        // 加赛表头:号码 | 选手名 | [圈] | 裁判1..n | 总分 | 结果
        List<List<String>> tbHead = new ArrayList<>();
        tbHead.add(List.of("号码"));
        tbHead.add(List.of("选手名"));
        if (multiCircle) {
            tbHead.add(List.of("圈"));
        }
        for (TReferee r : referees) {
            tbHead.add(List.of(StringUtils.defaultString(r.getName(), "裁判" + r.getId())));
        }
        tbHead.add(List.of("总分"));
        tbHead.add(List.of("结果"));

        // 组装工作表:主表 + 二海/三海…各一张
        List<ExcelUtil.RawSheet> sheets = new ArrayList<>();
        sheets.add(new ExcelUtil.RawSheet("海选成绩", head, rows));
        java.util.Set<String> usedSheetNames = new java.util.HashSet<>();
        usedSheetNames.add("海选成绩");
        for (AuditionResultVo.TiebreakerItem tb : result.getTiebreakers()) {
            List<List<Object>> tbRows = new ArrayList<>();
            // 加赛表按该轮加赛总分降序(同分再按号码牌),即「谁赢了加赛谁在前」
            List<AuditionResultVo.CompetitorItem> tbItems = new ArrayList<>(tb.getCompetitors());
            tbItems.sort(Comparator
                .comparing((AuditionResultVo.CompetitorItem c) -> c.getScore() == null ? new BigDecimal(-1) : c.getScore())
                .reversed()
                .thenComparingInt(c -> parseCompetitorNumber(c.getNumber())));
            for (AuditionResultVo.CompetitorItem c : tbItems) {
                tbRows.add(auditionExportRow(c, referees, false, zoneLabels, multiCircle));
            }
            String sheetName = tiebreakerSheetName(tb.getRound());
            String zoneLabel = zoneLabelOf(zoneLabels, tb.getZone());
            if (multiCircle && StringUtils.isNotBlank(zoneLabel)) {
                sheetName += "·" + zoneLabel;
            }
            if (!usedSheetNames.add(sheetName) && tb.getZone() != null) {
                // 同名裁判同时绑多个圈等极端情况:追加圈号保证 sheet 不重名
                sheetName += "·" + tb.getZone();
                usedSheetNames.add(sheetName);
            }
            sheets.add(new ExcelUtil.RawSheet(sheetName, tbHead, tbRows));
        }

        try {
            org.dromara.common.core.utils.file.FileUtils.setAttachmentResponseHeader(
                response, "海选结果-" + stage.getName() + ".xlsx");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8");
            try (jakarta.servlet.ServletOutputStream os = response.getOutputStream()) {
                ExcelUtil.exportSheets(sheets, os);
            }
        } catch (java.io.IOException e) {
            throw new ServiceException("导出海选结果失败: {}", e.getMessage());
        }
    }

    /**
     * 加赛得分:competitorId -> 最深一轮(二海→三海…)的加赛总分。
     * 只用于同原始分时的先后,不参与主表总分。
     */
    private Map<Long, BigDecimal> auditionTiebreakScoreMap(List<AuditionResultVo.TiebreakerItem> tiebreakers) {
        Map<Long, BigDecimal> byCid = new HashMap<>();
        if (tiebreakers == null || tiebreakers.isEmpty()) {
            return byCid;
        }
        List<AuditionResultVo.TiebreakerItem> ordered = new ArrayList<>(tiebreakers);
        ordered.sort(Comparator.comparingInt(t -> t.getRound() == null ? 0 : t.getRound()));
        for (AuditionResultVo.TiebreakerItem tb : ordered) {
            if (tb.getCompetitors() == null) {
                continue;
            }
            for (AuditionResultVo.CompetitorItem c : tb.getCompetitors()) {
                if (c.getCompetitorId() != null && c.getScore() != null) {
                    byCid.put(c.getCompetitorId(), c.getScore());
                }
            }
        }
        return byCid;
    }

    /**
     * 海选导出主表排序:圈序 → 原始分降序 → 加赛分降序 → 号码牌升序
     * (与名单页「分数排名」同一口径,保证二海选手按加赛成绩排在前面)。
     */
    private Comparator<AuditionResultVo.CompetitorItem> auditionExportComparator(
        List<String> zoneOrder, Map<Long, BigDecimal> tiebreakScore) {
        Comparator<AuditionResultVo.CompetitorItem> byZone = Comparator.comparingInt(c -> {
            int idx = c.getZone() == null ? -1 : zoneOrder.indexOf(c.getZone());
            return idx < 0 ? Integer.MAX_VALUE : idx;
        });
        Comparator<AuditionResultVo.CompetitorItem> byScore = Comparator.comparing(
                (AuditionResultVo.CompetitorItem c) -> c.getScore() == null ? new BigDecimal(-1) : c.getScore())
            .reversed();
        Comparator<AuditionResultVo.CompetitorItem> byTiebreak = Comparator.comparing(
                (AuditionResultVo.CompetitorItem c) -> tiebreakScore.getOrDefault(c.getCompetitorId(), new BigDecimal(-1)))
            .reversed();
        Comparator<AuditionResultVo.CompetitorItem> byNumber =
            Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber()));
        return byZone.thenComparing(byScore).thenComparing(byTiebreak).thenComparing(byNumber);
    }

    /** 海选导出行:主表最后一列为排名,加赛表最后一列为结果 */
    private List<Object> auditionExportRow(AuditionResultVo.CompetitorItem c,
                                           List<TReferee> referees, boolean mainSheet,
                                           Map<String, String> zoneLabels, boolean multiCircle) {
        Map<Long, BigDecimal> refMap = c.getRefereeScores() == null ? Map.of()
            : c.getRefereeScores().stream()
                .collect(Collectors.toMap(AuditionResultVo.RefereeScoreItem::getRefereeId,
                    AuditionResultVo.RefereeScoreItem::getScore, (a, b) -> a));
        List<Object> row = new ArrayList<>();
        row.add(c.getNumber() == null ? "" : c.getNumber());
        row.add(c.getName() == null ? "" : c.getName());
        if (multiCircle) {
            row.add(zoneLabelOf(zoneLabels, c.getZone()));
        }
        BigDecimal total = BigDecimal.ZERO;
        int scoredRefs = 0;
        for (TReferee r : referees) {
            BigDecimal v = refMap.get(r.getId());
            row.add(v == null ? "" : v.stripTrailingZeros().toPlainString());
            if (v != null) {
                total = total.add(v);
                scoredRefs++;
            }
        }
        row.add(scoredRefs == 0 ? "" : total.stripTrailingZeros().toPlainString());
        row.add(mainSheet
            ? (c.getFinalRank() == null ? "" : c.getFinalRank())
            : auditionResultText(c.getOutcomeStatus()));
        return row;
    }

    /** 导出用圈标签:多圈时按圈显示裁判名(无裁判回退「第N圈」) */
    private Map<String, String> buildAuditionZoneLabelMap(TStage stage, List<TReferee> referees) {
        List<TMatch> zones = auditionCircles(stage);
        if (zones.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = referees.stream()
            .collect(Collectors.toMap(TReferee::getId,
                r -> StringUtils.defaultString(r.getName(), "裁判" + r.getId()), (a, b) -> a));
        List<Long> zoneIds = zones.stream().map(TMatch::getId).toList();
        List<TMatchReferee> refRows = zoneIds.isEmpty() ? List.of()
            : matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .in(TMatchReferee::getMatchId, zoneIds)
                .orderByAsc(TMatchReferee::getRefereeId));
        Map<Long, List<String>> namesByMatch = new HashMap<>();
        for (TMatchReferee mr : refRows) {
            String nm = nameById.get(mr.getRefereeId());
            if (nm == null) {
                continue;
            }
            namesByMatch.computeIfAbsent(mr.getMatchId(), k -> new ArrayList<>()).add(nm);
        }
        Map<String, String> labels = new HashMap<>();
        for (TMatch z : zones) {
            List<String> names = namesByMatch.get(z.getId());
            labels.put(z.getDisplayZone(),
                names == null || names.isEmpty()
                    ? "第" + StringUtils.defaultString(z.getDisplayZone(), "").replace("ZONE-", "") + "圈"
                    : String.join(" / ", names));
        }
        return labels;
    }

    private String zoneLabelOf(Map<String, String> zoneLabels, String zone) {
        if (zone == null || zone.isBlank() || zoneLabels.isEmpty()) {
            return "";
        }
        return zoneLabels.getOrDefault(zone, "");
    }

    /**
     * 查询海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
     * 二海分数只用于同分者决出晋级顺序,不计入原始总分;
     * 导出与前端各组件均消费本结果,不再各自聚合。
     */
    @Override
    public AuditionResultVo queryAuditionResult(Long stageId) {
        TStage stage = mustGetStage(stageId);
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        List<TMatch> mainMatches = new ArrayList<>();
        List<TMatch> tbMatches = new ArrayList<>();
        for (TMatch m : matches) {
            if (settlementSupport.isTiebreaker(m)) {
                tbMatches.add(m);
            } else {
                mainMatches.add(m);
            }
        }
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));
        Map<Long, String> refNameById = referees.stream()
            .collect(Collectors.toMap(TReferee::getId,
                r -> StringUtils.defaultString(r.getName(), "裁判" + r.getId()), (a, b) -> a));

        AuditionResultVo vo = new AuditionResultVo();
        vo.setCompetitors(buildAuditionMainItems(mainMatches, referees, refNameById));
        vo.setTiebreakers(buildAuditionTiebreakers(tbMatches, referees, refNameById));
        return vo;
    }

    /** 原始海选场参与方明细:原始总分(不含二海)、场次排名、结果、各裁判分;按最终排名升序 */
    private List<AuditionResultVo.CompetitorItem> buildAuditionMainItems(List<TMatch> mainMatches,
                                                                         List<TReferee> referees,
                                                                         Map<Long, String> refNameById) {
        if (mainMatches.isEmpty()) {
            return List.of();
        }
        List<Long> matchIds = mainMatches.stream().map(TMatch::getId).toList();
        Map<Long, TMatch> matchById = mainMatches.stream()
            .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .isNotNull(TMatchParticipant::getCompetitorId));
        Map<String, BigDecimal> scoreByRef = roundScoreByRef(matchIds);
        Map<Long, TCompetitor> compById = competitorById(parts);
        List<AuditionResultVo.CompetitorItem> items = new ArrayList<>();
        for (TMatchParticipant p : parts) {
            items.add(toCompetitorItem(p, compById, matchById, scoreByRef, referees, refNameById));
        }
        items.sort(Comparator
            .comparing((AuditionResultVo.CompetitorItem i) -> i.getFinalRank() == null ? Long.MAX_VALUE : i.getFinalRank())
            .thenComparingInt(i -> parseCompetitorNumber(i.getNumber())));
        return items;
    }

    /** 二海/三海…:按「加赛深度 + 圈」分组,每圈独立一份明细(多圈时互不混淆),参与方按号码升序 */
    private List<AuditionResultVo.TiebreakerItem> buildAuditionTiebreakers(List<TMatch> tbMatches,
                                                                           List<TReferee> referees,
                                                                           Map<Long, String> refNameById) {
        // 加赛深度 -> 圈(displayZone,按首次出现序) -> 加赛场次
        Map<Integer, LinkedHashMap<String, List<TMatch>>> byDepthZone = new TreeMap<>();
        for (TMatch m : tbMatches) {
            String nm = m.getName() == null ? "" : m.getName();
            int depth = 0;
            for (int i = nm.indexOf("加赛"); i >= 0; i = nm.indexOf("加赛", i + 2)) {
                depth++;
            }
            String zone = m.getDisplayZone();
            byDepthZone.computeIfAbsent(Math.max(1, depth), k -> new LinkedHashMap<>())
                .computeIfAbsent(zone, k -> new ArrayList<>())
                .add(m);
        }
        List<AuditionResultVo.TiebreakerItem> result = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashMap<String, List<TMatch>>> depthEntry : byDepthZone.entrySet()) {
            for (Map.Entry<String, List<TMatch>> zoneEntry : depthEntry.getValue().entrySet()) {
                List<TMatch> group = zoneEntry.getValue();
                List<Long> ids = group.stream().map(TMatch::getId).toList();
                Map<Long, TMatch> matchById = group.stream()
                    .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
                List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, ids)
                    .isNotNull(TMatchParticipant::getCompetitorId));
                Map<String, BigDecimal> scoreByRef = roundScoreByRef(ids);
                Map<Long, TCompetitor> compById = competitorById(parts);
                List<AuditionResultVo.CompetitorItem> items = new ArrayList<>();
                for (TMatchParticipant p : parts) {
                    items.add(toCompetitorItem(p, compById, matchById, scoreByRef, referees, refNameById));
                }
                items.sort(Comparator.comparingInt(i -> parseCompetitorNumber(i.getNumber())));
                AuditionResultVo.TiebreakerItem tb = new AuditionResultVo.TiebreakerItem();
                tb.setRound(depthEntry.getKey());
                tb.setMatchId(group.get(0).getId());
                tb.setName(group.get(0).getName());
                tb.setZone(group.get(0).getDisplayZone());
                tb.setCompetitors(items);
                result.add(tb);
            }
        }
        return result;
    }

    /** 指定场次的 (competitorId:refereeId) -> 累计分 */
    private Map<String, BigDecimal> roundScoreByRef(List<Long> matchIds) {
        List<Long> roundIds = matchIds.isEmpty() ? List.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
        return roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
                .stream()
                .filter(s -> s.getCompetitorId() != null && s.getRefereeId() != null && s.getScore() != null)
                .collect(Collectors.toMap(
                    s -> s.getCompetitorId() + ":" + s.getRefereeId(),
                    TRoundScore::getScore,
                    BigDecimal::add));
    }

    /** 参与方 -> 参赛单位映射 */
    private Map<Long, TCompetitor> competitorById(List<TMatchParticipant> parts) {
        List<Long> cids = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        return cids.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(cids).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
    }

    /** 参赛方行 -> 统一结果项(号码/名称/总分/排名/结果/各裁判分) */
    private AuditionResultVo.CompetitorItem toCompetitorItem(TMatchParticipant p,
                                                             Map<Long, TCompetitor> compById,
                                                             Map<Long, TMatch> matchById,
                                                             Map<String, BigDecimal> scoreByRef,
                                                             List<TReferee> referees,
                                                             Map<Long, String> refNameById) {
        TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
        AuditionResultVo.CompetitorItem item = new AuditionResultVo.CompetitorItem();
        item.setCompetitorId(p.getCompetitorId());
        item.setNumber(c == null ? null : c.getNumber());
        item.setName(c == null ? null : c.getName());
        TMatch m = p.getMatchId() == null ? null : matchById.get(p.getMatchId());
        item.setZone(m == null ? null : m.getDisplayZone());
        item.setScore(p.getScoreValue());
        item.setRankInMatch(p.getRankInMatch());
        item.setOutcomeStatus(p.getOutcomeStatus());
        item.setFinalRank(c == null ? null : c.getFinalRank());
        List<AuditionResultVo.RefereeScoreItem> refScores = new ArrayList<>();
        if (p.getCompetitorId() != null) {
            for (TReferee r : referees) {
                BigDecimal v = scoreByRef.get(p.getCompetitorId() + ":" + r.getId());
                if (v != null) {
                    AuditionResultVo.RefereeScoreItem rs = new AuditionResultVo.RefereeScoreItem();
                    rs.setRefereeId(r.getId());
                    rs.setRefereeName(refNameById.get(r.getId()));
                    rs.setScore(v);
                    refScores.add(rs);
                }
            }
        }
        item.setRefereeScores(refScores);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetStageToDraft(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)状态的赛段可重置为草稿,当前: {}", stage.getStatus());
        }
        // 级联清除场次/轮次/参赛明细/打分
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (!matches.isEmpty()) {
            List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds)
                    .select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds));
            }
            participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds));
            matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds));
            matchMapper.deleteByIds(matchIds);
        }
        // 名单快照:删除 apply 写入的行(from_roster=1)及其成员,名单 applied 回退,可重新装配;
        // 保留签到/手工 GUEST 等非快照行按旧语义回退待定
        List<TCompetitor> snapshotRows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getFromRoster, 1L));
        if (!snapshotRows.isEmpty()) {
            List<Long> snapshotRowIds = snapshotRows.stream().map(TCompetitor::getId).toList();
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, snapshotRowIds));
            competitorMapper.deleteByIds(snapshotRowIds);
        }
        rosterService.resetByTarget(stageId);
        // 参赛方回退未开始(保留种子位,可重新 setSeedOrder/initialize)
        competitorMapper.update(null, Wrappers.<TCompetitor>lambdaUpdate()
            .eq(TCompetitor::getStageId, stageId)
            .set(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode())
            .set(TCompetitor::getFinalRank, null));
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setIsInitialized(0L);
        upd.setStatus(StageConstants.STAGE_DRAFT);
        stageMapper.updateById(upd);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        log.info("赛段[{}]已重置为草稿:清除{}场对阵,参赛方回退待定", stageId, matches.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int calculateAdvancement(Long stageId) {
        // 导播台「跳过中间态确认」入口:统一委托名单整单装配(唯一写库内核)
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅 SETTLED 状态的赛段可计算晋级");
        }
        Long nextStageId = resolveNextStageId(stage);
        if (nextStageId == null) {
            return 0;
        }
        if (stageMapper.selectById(nextStageId) == null) {
            return 0;
        }
        return rosterService.applyRoster(nextStageId, null);
    }

    /** 下一赛段(晋级者要写进哪一段):以 {@code next_stage_id} 链为唯一事实源。 */
    private Long resolveNextStageId(TStage stage) {
        return stage.getNextStageId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAutoSettleTiebreaker(Long matchId) {
        // 加赛「全员打分后自动结算」的判定与落库都在海选结算策略里,本类只做事务边界与转发
        return auditionStageSettler.tryAutoSettleTiebreaker(matchId);
    }

    @Override
    public void setMatchCurrentCompetitor(Long matchId, Long competitorId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        // 现场标记走跨实例缓存(Redis,单机降级为进程内),不落库——见 MatchCurrentCompetitorStore
        currentCompetitorStore.mark(matchId, competitorId);
        // 广播:大屏 widget 与导播台按事件刷新当前标记
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "stage");
    }

    @Override
    public Long getMatchCurrentCompetitor(Long matchId) {
        return currentCompetitorStore.current(matchId);
    }

    // ==================== 自由对抗(手动加场 + 手动晋级) ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFreeMatch(Long stageId, Long competitorAId, Long competitorBId) {
        TStage stage = mustGetStage(stageId);
        requireFreeMatchStage(stage);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段尚未开始,无法添加对战;请先在导播台点「开始赛段」");
        }
        if (competitorAId == null || competitorBId == null) {
            throw new ServiceException("请选择两名对战的选手");
        }
        if (competitorAId.equals(competitorBId)) {
            throw new ServiceException("同一名选手不能与自己对战");
        }
        TCompetitor a = requireStageCompetitor(stageId, competitorAId);
        TCompetitor b = requireStageCompetitor(stageId, competitorBId);

        long count = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        TMatch m = new TMatch();
        m.setTournamentId(stage.getTournamentId());
        m.setTenantId(stage.getTenantId());
        m.setStageId(stageId);
        m.setName("第" + (count + 1) + "场");
        m.setDisplayRow(count + 1);
        m.setDisplayCol(1L);
        m.setDisplayZone("CENTER");
        m.setStatus(StageConstants.MATCH_PENDING);
        m.setMatchMode(MatchModeEnum.STANDARD.getCode());
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);

        TMatchRound round = new TMatchRound();
        round.setTournamentId(stage.getTournamentId());
        round.setTenantId(stage.getTenantId());
        round.setMatchId(m.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_PENDING);
        matchRoundMapper.insert(round);

        insertFreeMatchParticipant(m, a.getId(), 0L);
        insertFreeMatchParticipant(m, b.getId(), 1L);

        log.info("自由对抗赛段[{}]手动添加第{}场对战:{} vs {}", stageId, count + 1, a.getName(), b.getName());
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, m.getId(), "match");
        return m.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFreeMatch(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        TStage stage = mustGetStage(match.getStageId());
        requireFreeMatchStage(stage);
        if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
            throw new ServiceException("已结算的对战不能删除;如需重来请先重置该场");
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, matchId));
        matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, matchId));
        matchMapper.deleteById(matchId);
        log.info("自由对抗赛段[{}]删除对战场次[{}]", stage.getId(), matchId);
        refereeSseNotifier.notifyStage(stage.getId(), "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int selectFreeMatchAdvancers(Long stageId, List<Long> competitorIds) {
        TStage stage = mustGetStage(stageId);
        requireFreeMatchStage(stage);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段未在进行中,无法选择晋级者");
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .orderByAsc(TCompetitor::getNumber)
            .orderByAsc(TCompetitor::getId));
        if (comps.isEmpty()) {
            throw new ServiceException("赛段暂无参赛选手");
        }
        Set<Long> selected = competitorIds == null ? Set.of() : new LinkedHashSet<>(competitorIds);
        Set<Long> stageIds = comps.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        for (Long cid : selected) {
            if (cid == null || !stageIds.contains(cid)) {
                throw new ServiceException("选手[{}]不属于本赛段", cid);
            }
        }
        // 记录对战结果之外只做两件事:选中的标记晋级(名次按传入顺序),其余标记淘汰;退赛选手保持不动
        long rank = 1L;
        int advanced = 0;
        for (TCompetitor c : comps) {
            if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            boolean advance = selected.contains(c.getId());
            outcomeWriter.writeResult(c.getId(),
                advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                advance ? rank : null);
            if (advance) {
                rank++;
                advanced++;
            }
        }
        log.info("自由对抗赛段[{}]手动选定晋级 {} 人:{}", stageId, advanced, selected);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return advanced;
    }

    /** 校验赛段为自由对抗模式 */
    private void requireFreeMatchStage(TStage stage) {
        if (!StageModeEnum.FREE_MATCH.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅自由对抗赛段支持手动添加对战/选择晋级");
        }
    }

    /** 取本赛段参赛方,并排除已退赛选手 */
    private TCompetitor requireStageCompetitor(Long stageId, Long competitorId) {
        TCompetitor c = competitorMapper.selectById(competitorId);
        if (c == null || !Objects.equals(c.getStageId(), stageId)) {
            throw new ServiceException("选手不存在或不属于本赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
            throw new ServiceException("选手[{}]已退赛,无法安排对战", c.getName());
        }
        return c;
    }

    /** 自由对战场次的参赛方行 */
    private void insertFreeMatchParticipant(TMatch match, Long competitorId, Long slotIndex) {
        TMatchParticipant p = new TMatchParticipant();
        p.setTournamentId(match.getTournamentId());
        p.setTenantId(match.getTenantId());
        p.setMatchId(match.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(slotIndex);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustAdvancement(Long stageId, List<Long> competitorIds) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.RANK.getCode().equals(stage.getStageMode())
            && !StageModeEnum.GROUP.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅排名赛/小组赛赛段支持手动调整同分晋级");
        }
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅已结算(SETTLED)的排名赛赛段可调整同分晋级");
        }
        // 幂等:下一赛段若已接收晋级者,不允许再调整
        Long nextStageId = resolveNextStageId(stage);
        if (nextStageId != null) {
            long existed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, nextStageId)
                .isNotNull(TCompetitor::getSourceCompetitorId));
            if (existed > 0) {
                throw new ServiceException("下一赛段已接收晋级者,无法再调整同分晋级");
            }
        }
        List<TCompetitor> pending = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
        if (pending.isEmpty()) {
            throw new ServiceException("当前无待定(同分)参赛者需要调整");
        }
        if (competitorIds == null || competitorIds.isEmpty()) {
            throw new ServiceException("请指定要晋级的参赛者(全部待定者都传入即全部晋级)");
        }
        Set<Long> pendingIds = pending.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        for (Long cid : competitorIds) {
            if (cid == null || !pendingIds.contains(cid)) {
                throw new ServiceException("参赛者[{}]不在本赛段待定名单中", cid);
            }
        }

        // 已有晋级者的最大 finalRank 之后顺延分配
        long nextRank = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
                .select(TCompetitor::getFinalRank))
            .stream().mapToLong(c -> c.getFinalRank() == null ? 0L : c.getFinalRank())
            .max().orElse(0L) + 1L;

        Set<Long> advanceSet = new HashSet<>(competitorIds);
        for (Long cid : competitorIds) {
            markManualAdvanceResult(cid, OutcomeStatusEnum.ADVANCE.getCode(), nextRank++, stageId);
        }
        // 未选中的待定者淘汰
        for (TCompetitor c : pending) {
            if (!advanceSet.contains(c.getId())) {
                markManualAdvanceResult(c.getId(), OutcomeStatusEnum.ELIMINATED.getCode(), nextRank++, stageId);
            }
        }
        log.info("排名赛赛段[{}]手动调整同分晋级:{}人晋级,{}人淘汰",
            stageId, competitorIds.size(), pending.size() - competitorIds.size());
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return competitorIds.size();
    }

    /** 手动调整结果:更新 competitor 赛段级状态 + 本赛段全部场次的 participant 状态 */
    private void markManualAdvanceResult(Long competitorId, String outcome, Long finalRank, Long stageId) {
        outcomeWriter.writeResult(competitorId, outcome, finalRank);

        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (!matchIds.isEmpty()) {
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setOutcomeStatus(outcome);
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .in(TMatchParticipant::getMatchId, matchIds)
                .eq(TMatchParticipant::getCompetitorId, competitorId));
        }
    }

    @Override
    public RankDetailVo getRankDetail(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅排名赛赛段支持排名明细");
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        // 公布控制:MANUAL/BATCH 且未结算前隐藏分数与维度分
        boolean hidden = !StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            && rc != null && rc.getPublishMode() != null && !"AUTO".equalsIgnoreCase(rc.getPublishMode());
        AggregateRuleEnum refRule = AggregateRuleEnum.fromCode(
            rc != null && rc.getScoring() != null ? rc.getScoring().getRefereeAggregateRule() : null);
        java.math.BigDecimal trimRatio = rc != null && rc.getScoring() != null ? rc.getScoring().getTrimRatio() : null;
        List<DimensionConfig> dims = rc != null && rc.getScoring() != null ? rc.getScoring().getDimensions() : null;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId)
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();

        Map<Long, List<TMatchParticipant>> partsByMatch = matchIds.isEmpty() ? Map.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds))
                .stream().collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        List<Long> roundIds = matchIds.isEmpty() ? List.of() : matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Map<Long, List<TMatchRound>> roundsByMatch = matchIds.isEmpty() ? Map.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds))
                .stream().collect(Collectors.groupingBy(TMatchRound::getMatchId));
        Map<Long, List<TRoundScore>> scoresByRound = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
                .stream().collect(Collectors.groupingBy(TRoundScore::getRoundId));

        List<Long> compIds = partsByMatch.values().stream()
            .flatMap(List::stream)
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        RankDetailVo vo = new RankDetailVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStatus(stage.getStatus());
        List<RankDetailVo.CircleRank> circles = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            TMatch m = matches.get(i);
            RankDetailVo.CircleRank cr = new RankDetailVo.CircleRank();
            cr.setZone(m.getDisplayZone());
            cr.setTitle(matches.size() > 1 ? "第" + (i + 1) + "圈" : "排名");
            List<RankDetailVo.CompetitorRank> comps = new ArrayList<>();
            for (TMatchParticipant p : partsByMatch.getOrDefault(m.getId(), List.of())) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                RankDetailVo.CompetitorRank c = new RankDetailVo.CompetitorRank();
                c.setCompetitorId(p.getCompetitorId());
                TCompetitor comp = compMap.get(p.getCompetitorId());
                c.setName(comp != null ? comp.getName() : null);
                c.setNumber(comp != null ? comp.getNumber() : null);
                c.setRankInMatch(p.getRankInMatch());
                c.setScoreValue(hidden ? null : p.getScoreValue());
                if (!hidden) {
                    c.setDimensions(aggregateCompetitorDimensions(
                        m, p.getCompetitorId(), roundsByMatch, scoresByRound, refRule, trimRatio, dims));
                }
                comps.add(c);
            }
            cr.setCompetitors(comps);
            circles.add(cr);
        }
        vo.setCircles(circles);
        return vo;
    }

    /** 聚合某参赛者跨全部轮次(逐选手轮次)的各维度分:按裁判间汇总规则合并多裁判分 */
    private List<RankDetailVo.DimensionScore> aggregateCompetitorDimensions(
            TMatch match, Long competitorId,
            Map<Long, List<TMatchRound>> roundsByMatch,
            Map<Long, List<TRoundScore>> scoresByRound,
            AggregateRuleEnum refRule, java.math.BigDecimal trimRatio,
            List<DimensionConfig> dims) {
        Map<String, List<java.math.BigDecimal>> byDim = new LinkedHashMap<>();
        for (TMatchRound r : roundsByMatch.getOrDefault(match.getId(), List.of())) {
            if (!Objects.equals(r.getCompetitorId(), competitorId)) {
                continue;
            }
            for (TRoundScore s : scoresByRound.getOrDefault(r.getId(), List.of())) {
                if (s.getCompetitorId() == null || s.getScore() == null) {
                    continue;
                }
                String dim = s.getDimension() != null ? s.getDimension() : StageConstants.DIMENSION_MAIN;
                byDim.computeIfAbsent(dim, k -> new ArrayList<>()).add(s.getScore());
            }
        }
        List<RankDetailVo.DimensionScore> result = new ArrayList<>();
        if (dims != null && !dims.isEmpty()) {
            // 按配置维度顺序返回,保证展示稳定
            for (DimensionConfig d : dims) {
                RankDetailVo.DimensionScore ds = new RankDetailVo.DimensionScore();
                ds.setKey(d.getKey());
                ds.setName(d.getName());
                ds.setMaxScore(d.getMaxScore());
                ds.setScore(ScoreAggregator.aggregate(byDim.getOrDefault(d.getKey(), List.of()), refRule, trimRatio));
                result.add(ds);
            }
        } else {
            for (Map.Entry<String, List<java.math.BigDecimal>> e : byDim.entrySet()) {
                RankDetailVo.DimensionScore ds = new RankDetailVo.DimensionScore();
                ds.setKey(e.getKey());
                ds.setName(e.getKey());
                ds.setScore(ScoreAggregator.aggregate(e.getValue(), refRule, trimRatio));
                result.add(ds);
            }
        }
        return result;
    }

    private TStage mustGetStage(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        return stage;
    }

    private static String matchKey(int round, int matchIndex) {
        return round + ":" + matchIndex;
    }
}
