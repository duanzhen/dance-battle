package com.dance.street.game.service.impl;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.idev.excel.ExcelWriter;
import cn.idev.excel.write.metadata.WriteSheet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import com.dance.street.game.engine.common.PairingModeResolver;
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
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.TransitionConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.MatchPlan;
import com.dance.street.game.engine.generator.SlotPlan;
import com.dance.street.game.engine.generator.StageGeneratorFactory;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.ScoreAggregator;
import com.dance.street.game.engine.scoring.ScoringEngine;
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
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final ScoringEngine scoringEngine = new ScoringEngine();
    private final TRoundScoreMapper roundScoreMapper;
    private final ITCompetitorService competitorService;
    private final ITStageService stageService;
    private final StageGeneratorFactory generatorFactory = new StageGeneratorFactory();
    private final ITScoredMatchService scoredMatchService;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ITRefereeStageService refereeStageService;
    private final ITStageRosterService rosterService;

    /** 海选大屏「当前上场选手」标记(仅内存,现场标记,不落库):matchId -> competitorId */
    private final Map<Long, Long> matchCurrentCompetitor = new ConcurrentHashMap<>();

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
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            c.setSeedRank((long) (i + 1));
            competitorMapper.updateById(c);
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
            // 海选已配置分圈但当前无人签到(预建空圈)时跳过自动初始化,
            // 允许抽号前先生成按配置的空圈结构,待签到后再落圈;
            // 其余场景保持原逻辑(初始化会把名单锁定,不改变业务状态)
            boolean emptyPlannedAudition = isAudition && plannedCircleCount(stage) > 1
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
        // 单赛段多轮淘汰赛(未开启单轮模式)结算后只有冠军可晋级到下一赛段:
        // 每场败者都会被标记淘汰,advanceCount 大于 1 的配置会被静默忽略,这里直接拒绝生成
        if (StageModeEnum.KNOCKOUT.equals(mode)
            && rc != null && rc.getKnockout() != null
            && !Boolean.TRUE.equals(rc.getKnockout().getSingleRound())
            && rc.getKnockout().getAdvanceCount() != null
            && rc.getKnockout().getAdvanceCount() > 1) {
            throw new ServiceException(
                "单赛段多轮淘汰赛(未开启单轮模式)结算后仅冠军可晋级;当前配置晋级 {} 人,"
                    + "如需 {} 人晋级到下一赛段请开启「单轮模式(每轮一赛段)」",
                rc.getKnockout().getAdvanceCount(), rc.getKnockout().getAdvanceCount());
        }
        // 承接上一淘汰赛胜者:按胜者位置顺序配对(SEQUENTIAL),不受本赛段 SEED 配置影响;
        // 从海选赛进入的淘汰赛,未显式配置时默认标准种子对位(1-16、2-15)
        if (StageModeEnum.KNOCKOUT.equals(mode)
            && rc != null && rc.getKnockout() != null) {
            // 与预排(getPreBracket)口径一致:prev_stage_id 缺失时按 next 指针反查上一赛段,
            // 避免链表指针正常但 prev_stage_id 为空时误走 SEED 头尾交叉
            TStage prev = resolvePrevStage(stage);
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
            int advanceCount = readStageAdvanceCount(stage);
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

        // 淘汰赛轮次序号:沿赛段链从第一个淘汰赛开始计数,用于场次命名 第{场次}场
        if (StageModeEnum.KNOCKOUT.equals(mode) && rc != null) {
            rc.setKnockoutRound(knockoutRoundNo(stage));
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
        List<TMatch> circles = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (circles.isEmpty()) {
            return;
        }
        // 幂等:重建前先清掉该批场次已有的圈-裁判绑定
        List<Long> circleIds = circles.stream().map(TMatch::getId).toList();
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
            .in(TMatchReferee::getMatchId, circleIds));

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> cfg = rc != null ? rc.getCircleRefereeIds() : null;
        boolean useConfig = cfg != null && cfg.size() == circles.size();
        List<Long> stageRefereeIds = refereeStageService.getRefereeIdsByStageId(stage.getId());
        if (!useConfig && stageRefereeIds.isEmpty()) {
            return;
        }
        boolean oneToOne = !useConfig && circles.size() == stageRefereeIds.size();
        int assignedCount = 0;
        for (int i = 0; i < circles.size(); i++) {
            List<Long> assigned;
            if (useConfig) {
                assigned = cfg.get(i) == null ? List.of() : cfg.get(i);
            } else {
                assigned = oneToOne ? List.of(stageRefereeIds.get(i)) : stageRefereeIds;
            }
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
            || plannedCircleCount(stage) <= 1
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
        int planned = plannedCircleCount(stage);
        List<TMatch> zones = auditionZoneMatches(stageId);
        if (zones.isEmpty()) {
            // 尚无圈场次(或只有旧 CENTER):全量生成计划圈
            RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
            boolean randomSplit = rc != null && Boolean.TRUE.equals(rc.getRandomSplit());
            GenerateMatchesBo gm = new GenerateMatchesBo();
            gm.setStageId(stageId);
            generateMatchesInternal(gm, randomSplit);
            log.info("海选赛段[{}]全量生成计划圈完成", stageId);
            return;
        }
        if (zones.size() < planned) {
            // 只允许增加圈:在末尾追加空白 ZONE match,原圈及已落圈选手保持不变
            addMissingAuditionCircles(stage, planned);
            log.info("海选赛段[{}]按配置追加空圈至{}圈完成", stageId, planned);
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
        List<Long> queue = computeArenaQueue(stageId);
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
        TCompetitor upd = new TCompetitor();
        upd.setId(competitorId);
        upd.setOutcomeStatus(OutcomeStatusEnum.WITHDRAWN.getCode());
        competitorMapper.updateById(upd);
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
        upd.setRemark(appendArenaSkipMark(comp.getRemark()));
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
        List<Long> queue = computeArenaQueue(stageId);
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
        // 0 参赛者的场次:仅首轮视为双边轮空可结算;后续轮次是等待上游胜者填入的占位,
        // 不能按轮空结算,否则整条淘汰链会在开赛瞬间塌掉
        if (real.isEmpty() && m.getDisplayCol() != null && m.getDisplayCol() > 1L) {
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
        markMatchSettled(m);
        return true;
    }

    /** 轮空胜者去向:填下游场次占位;finalMatch 则标记晋级下一赛段 */
    private void resolveKnockoutByeWinner(TMatch match, Long winnerCompetitorId) {
        Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(match.getPromotionRule());
        PromotionTarget winnerTarget = rule.get("1");
        if (winnerTarget == null) {
            return;
        }
        if (StageConstants.ACTION_FINAL_ADVANCE.equals(winnerTarget.getAction())) {
            markCompetitorAdvance(winnerCompetitorId, match);
        } else if (StageConstants.ACTION_ADVANCE.equals(winnerTarget.getAction())
            && winnerTarget.getTargetMatchId() != null && winnerTarget.getTargetSlot() != null) {
            fillDownstreamSlot(match, winnerTarget.getTargetMatchId(),
                winnerTarget.getTargetSlot().longValue(), winnerCompetitorId);
        }
    }

    /**
     * 胜者填入下游场次占位:占位行已存在(旧版预建)则更新;
     * 不存在(当前生成对阵时空槽不落 participant 行)则补插。
     */
    private void fillDownstreamSlot(TMatch sourceMatch, Long targetMatchId, Long targetSlot, Long winnerCompetitorId) {
        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setCompetitorId(winnerCompetitorId);
        int affected = participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, targetMatchId)
            .eq(TMatchParticipant::getDisplaySlotIndex, targetSlot));
        if (affected > 0) {
            return;
        }
        TMatchParticipant np = new TMatchParticipant();
        np.setTenantId(sourceMatch.getTenantId());
        np.setTournamentId(sourceMatch.getTournamentId());
        np.setMatchId(targetMatchId);
        np.setCompetitorId(winnerCompetitorId);
        np.setDisplaySlotIndex(targetSlot);
        np.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(np);
        log.info("胜者[{}]补插到下游场次[{}]占位(slot={})", winnerCompetitorId, targetMatchId, targetSlot);
    }

    /** 轮空胜者晋级标记:finalRank=场次位置(与正常结算一致),outcomeStatus=ADVANCE */
    private void markCompetitorAdvance(Long competitorId, TMatch match) {
        Long rank = match.getDisplayRow() != null ? match.getDisplayRow() + 1 : null;
        if (rank == null) {
            long cnt = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, match.getStageId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
            rank = cnt + 1;
        }
        TCompetitor cupd = new TCompetitor();
        cupd.setId(competitorId);
        cupd.setFinalRank(rank);
        cupd.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        competitorMapper.updateById(cupd);
    }

    /** 场次与轮次置为已结算并通知裁判端/赛事事件 */
    private void markMatchSettled(TMatch match) {
        TMatch mUpd = new TMatch();
        mUpd.setId(match.getId());
        mUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchMapper.updateById(mUpd);
        TMatchRound roundUpd = new TMatchRound();
        roundUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
            .eq(TMatchRound::getMatchId, match.getId()));
        refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "match");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "match");
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
        Map<Long, Integer> points = arenaPoints(stageId);
        List<Long> queue = computeArenaQueue(stageId);
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

    /**
     * 擂台赛轮转队列:初始 = 签到顺序(seedRank),回放已结算对决重排。
     * 每场对决:胜者留在队首,败者排到队尾,其余保持相对顺序;
     * 平局时擂主(slot1)与挑战者(slot2)均排到队尾(保持原相对顺序)。
     */
    private List<Long> computeArenaQueue(Long stageId) {
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            // 弃权选手不参与排队/对阵/排名
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        // 临时弃权标记:有标记的选手固定排在队尾(按标记时间),避免被"胜者守擂"重放顶回队首
        List<TCompetitor> normal = new ArrayList<>();
        List<TCompetitor> skipped = new ArrayList<>();
        for (TCompetitor c : comps) {
            if (arenaSkipSeq(c) >= 0) {
                skipped.add(c);
            } else {
                normal.add(c);
            }
        }
        skipped.sort(Comparator.comparingLong(this::arenaSkipSeq).thenComparing(TCompetitor::getId));
        List<Long> queue = normal.stream().map(TCompetitor::getId).collect(Collectors.toCollection(ArrayList::new));

        List<TMatch> settled = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED)
            .orderByAsc(TMatch::getId));
        for (TMatch m : settled) {
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            // 平局:擂主(slot1)与挑战者(slot2)均移到队尾,其余保持相对顺序
            boolean isDraw = parts.stream().anyMatch(p -> p.getCompetitorId() != null
                && MatchOutcomeEnum.DRAW.getCode().equals(p.getOutcomeStatus()));
            if (isDraw) {
                Long defender = null;
                Long challenger = null;
                for (TMatchParticipant p : parts) {
                    if (p.getCompetitorId() == null) {
                        continue;
                    }
                    if (p.getDisplaySlotIndex() != null && p.getDisplaySlotIndex() == 1L) {
                        defender = p.getCompetitorId();
                    } else if (p.getDisplaySlotIndex() != null && p.getDisplaySlotIndex() == 2L) {
                        challenger = p.getCompetitorId();
                    }
                }
                if (defender == null || challenger == null
                    || !queue.contains(defender) || !queue.contains(challenger)) {
                    // 异常数据(如中途改判/重启残留)跳过该场,保持当前队列
                    continue;
                }
                List<Long> next = new ArrayList<>();
                for (Long cid : queue) {
                    if (!cid.equals(defender) && !cid.equals(challenger)) {
                        next.add(cid);
                    }
                }
                next.add(defender);
                next.add(challenger);
                queue = next;
                continue;
            }
            Long winner = null;
            Long loser = null;
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                if (MatchOutcomeEnum.WIN.getCode().equals(p.getOutcomeStatus())) {
                    winner = p.getCompetitorId();
                } else if (MatchOutcomeEnum.LOSS.getCode().equals(p.getOutcomeStatus())) {
                    loser = p.getCompetitorId();
                }
            }
            if (winner == null || loser == null || !queue.contains(winner) || !queue.contains(loser)) {
                // 异常数据(如中途改判/重启残留)跳过该场,保持当前队列
                continue;
            }
            List<Long> next = new ArrayList<>();
            next.add(winner);
            for (Long cid : queue) {
                if (!cid.equals(winner) && !cid.equals(loser)) {
                    next.add(cid);
                }
            }
            next.add(loser);
            queue = next;
        }
        for (TCompetitor c : skipped) {
            queue.add(c.getId());
        }
        return queue;
    }

    /** 临时弃权标记前缀(存于 remark,格式 ARENA_SKIP:<时间戳>;可多个,取最后一次) */
    private static final String ARENA_SKIP_PREFIX = "ARENA_SKIP:";

    /** 读取临时弃权时间戳;无标记返回 -1 */
    private long arenaSkipSeq(TCompetitor c) {
        String r = c.getRemark();
        if (r == null || r.isBlank()) {
            return -1L;
        }
        int idx = r.lastIndexOf(ARENA_SKIP_PREFIX);
        if (idx < 0) {
            return -1L;
        }
        try {
            String rest = r.substring(idx + ARENA_SKIP_PREFIX.length());
            return Long.parseLong(rest.split(";")[0].trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private String appendArenaSkipMark(String remark) {
        String mark = ARENA_SKIP_PREFIX + System.currentTimeMillis();
        return (remark == null || remark.isBlank()) ? mark : remark + ";" + mark;
    }

    /** 擂台赛积分:统计本赛段全部场次中参赛者的胜场数(每胜一场 +1) */
    private Map<Long, Integer> arenaPoints(Long stageId) {
        Map<Long, Integer> wins = new HashMap<>();
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (matches.isEmpty()) {
            return wins;
        }
        // 平局双方各加1分:由赛段配置 drawBothScore 控制(默认关闭,只按胜场记分)
        TStage stage = stageMapper.selectById(stageId);
        RuleConfigHolder rc = stage != null ? RuleConfigParser.parse(stage.getRuleConfig()) : null;
        boolean drawBothScore = rc != null && Boolean.TRUE.equals(rc.getDrawBothScore());
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.WIN.getCode()));
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                wins.merge(p.getCompetitorId(), 1, Integer::sum);
            }
        }
        if (drawBothScore) {
            List<TMatchParticipant> draws = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .eq(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.DRAW.getCode()));
            for (TMatchParticipant p : draws) {
                if (p.getCompetitorId() != null) {
                    wins.merge(p.getCompetitorId(), 1, Integer::sum);
                }
            }
        }
        return wins;
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
        // DRAFT 也可一键开始:自动初始化后进入 PENDING,再生成对阵并开赛
        if (!StageConstants.STAGE_PENDING.equals(stage.getStatus())
            && !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅 DRAFT/PENDING 状态的赛段可开始,当前: {}", stage.getStatus());
        }
        // 海选圈默认为空:必须先配置至少一圈(人数/裁判/去向)才能开始
        if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            && plannedCircleCount(stage) < 1) {
            throw new ServiceException("海选尚未配置圈,请先在赛段配置中新增至少一圈(人数/裁判/去向)");
        }
        // 流程规范:上一赛段必须已结束(SETTLED),否则不允许开始本赛段
        TStage prev = resolvePrevStage(stage);
        if (stage.getPrevStageId() != null && prev == null) {
            throw new ServiceException("上一赛段不存在,无法开始本赛段");
        }
        if (prev != null) {
            if (!StageConstants.STAGE_SETTLED.equals(prev.getStatus())) {
                throw new ServiceException("上一赛段[{}]尚未结束,无法开始本赛段", prev.getName());
            }
            // 名单守卫(规则+覆盖+快照模型):含内部来源组的名单,
            // 已物化(CONFIRMED)/显式跳过(SKIPPED)可开赛;否则就绪度由源结算推导,
            // 有候选未确认则拦截,确无候选直接放行(不写任何状态)。
            List<TStageRosterVo> rosters = rosterService.listByTarget(stageId);
            if (!rosters.isEmpty()) {
                TStageRosterVo roster = rosters.get(0);
                boolean hasInternalSource = roster.getGroups() != null && roster.getGroups().stream()
                    .anyMatch(g -> g.getSourceStageId() != null);
                if (hasInternalSource) {
                    if (RosterConstants.ROSTER_CONFIRMED.equals(roster.getState())
                        || RosterConstants.ROSTER_SKIPPED.equals(roster.getState())) {
                        log.info("赛段[{}]名单已装配/跳过,开赛放行", stageId);
                    } else if (!rosterService.isRosterReady(roster.getId())) {
                        throw new ServiceException(
                            "赛段名单来源尚未全部结算,请等待来源赛段结束后再开始本赛段");
                    } else if (rosterService.hasAnyCandidate(roster.getId())) {
                        throw new ServiceException(
                            "赛段名单尚未确认,请先在中间态「确认名单」后再开始本赛段");
                    } else {
                        // 确无任何来源候选:本赛段不带人,直接放行(不再自动写 SKIPPED)
                        log.info("赛段[{}]名单无来源候选,本赛段不带人,直接开赛", stageId);
                    }
                }
            }
        }
        // 一键开赛:无对阵时自动初始化(如未初始化)并生成对阵,淘汰赛/小组赛/海选均适用
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        boolean isArena = StageModeEnum.ARENA.getCode().equals(stage.getStageMode());
        if (exist == 0) {
            if (!Long.valueOf(1L).equals(stage.getIsInitialized())) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stageId);
                initialize(initBo);
                // 初始化后重新读取赛段(状态/isInitialized 已更新)
                stage = mustGetStage(stageId);
            }
            if (!isArena) {
                GenerateMatchesBo gm = new GenerateMatchesBo();
                gm.setStageId(stageId);
                generateMatches(gm);
            }
        }
        // 轮空场次不在此自动结算:保持 PENDING,由导播台逐场点「开始」时再自动结束(见 settleByeMatch),
        // 保证淘汰赛的每一场(含轮空)都经过导播台确认
        stage.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(stage);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");

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
            TMatch upd = new TMatch();
            upd.setId(matches.get(i).getId());
            upd.setStatus(targetStatus);
            matchMapper.updateById(upd);
            TMatchRound roundUpd = new TMatchRound();
            roundUpd.setStatus(targetStatus);
            matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
                .eq(TMatchRound::getMatchId, matches.get(i).getId()));
        }
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

        // 海选分圈:圈结构按配置先建立(允许空圈),签到/抽号即落圈。
        // 尚无 ZONE 圈时按当前已签到名单全量生成(含本次新选手)后直接返回;
        // 已有圈但少于配置时只增量补空圈,不重建、不动原有圈。
        boolean auditionSplit = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            && plannedCircleCount(stage) > 1;
        if (auditionSplit) {
            List<TMatch> zones = auditionZoneMatches(stageId);
            if (zones.isEmpty()) {
                RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
                boolean randomSplit = rc != null && Boolean.TRUE.equals(rc.getRandomSplit());
                GenerateMatchesBo gm = new GenerateMatchesBo();
                gm.setStageId(stageId);
                generateMatchesInternal(gm, randomSplit);
                // 抽号页在「计划圈」阶段选中的目标圈:补建完成后把新选手落入所选圈
                if (zoneIndex != null && randomSplit) {
                    moveCompetitorToZoneByIndex(stageId, competitorId, zoneIndex);
                }
                return;
            }
            if (zones.size() < plannedCircleCount(stage)) {
                // 场次已开始(进行中)后禁止补建新圈,防止出现"进行中赛段里的未开始圈"
                boolean anyStarted = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stageId)
                    .ne(TMatch::getStatus, StageConstants.MATCH_PENDING)) > 0;
                if (!anyStarted) {
                    addMissingAuditionCircles(stage, plannedCircleCount(stage));
                }
            }
        }
        // 抽号页 ensureAuditionCircles 可能已按配置预建空圈但尚未初始化(DRAFT):
        // 第一位选手签到时补做初始化(锁定名单)以便挂入对应圈
        if (auditionSplit && !Long.valueOf(1L).equals(stage.getIsInitialized())
            && StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            long pending = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            if (pending > 0) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stageId);
                initialize(initBo);
                stage = mustGetStage(stageId);
            }
        }

        // 逐选手模式(AUDITION/RANK)补签到窗口:进行中/未开始均可挂入;
        // 状态已收敛为 DRAFT 后,已生成对阵但尚未开赛同样允许挂入,否则迟到者会从打分中"消失"。
        // 非逐选手赛制(淘汰/小组/擂台)仍保持原语义,不在生成后追加参赛方。
        boolean attachable = StageConstants.STAGE_GAMING.equals(stage.getStatus())
            || StageConstants.STAGE_PENDING.equals(stage.getStatus())
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
            .filter(m -> !(StringUtils.isNotBlank(m.getRemark()) && m.getRemark().startsWith("同分加赛")))
            .toList();
        if (candidates.isEmpty()) {
            return;
        }
        TMatch target;
        if (targetMatchId != null) {
            // 线下抽签指定圈:目标圈须为未结算的正式圈(加赛场次不可追加新人)
            target = candidates.stream()
                .filter(m -> m.getId().equals(targetMatchId))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                    "目标圈场次不存在、已结算或为加赛场次,无法加入;请选择其他圈"));
        } else {
            if (isNumberSplitCircle(stage, matches)) {
                // 按号码顺序均分的海选:新选手按其号码在整体号码序列中的位置落圈,
                // 与「生成对阵」的均分口径一致;目标圈已结算等异常时回退均衡落圈
                target = pickCircleByNumberOrder(matches, stage, competitorId);
                if (target == null || !candidates.contains(target)) {
                    target = pickCircleForCheckin(candidates, stage);
                }
            } else {
                // 随机分圈(或未分圈):自动择优——配置了每圈名额时选"剩余名额余量"最足的圈,
                // 未配置时等价于选人数最少的圈
                target = pickCircleForCheckin(candidates, stage);
            }
        }
        if (target == null) {
            return;
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
                || (StringUtils.isNotBlank(target.getRemark()) && target.getRemark().startsWith("同分加赛"))) {
                throw new ServiceException("目标圈场次已结算或为加赛场次,无法改入");
            }
        } else if (isNumberSplitCircle(stage, matches)) {
            // 按号分圈:号码已更新,按新号码在整体序列中的位置决定圈位
            target = pickCircleByNumberOrder(matches, stage, competitorId);
            if (target == null) {
                target = source;
            }
        } else {
            // 随机分圈/未分圈:不改圈,仅在原圈内按新号码重新排位
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
            TMatchParticipant slotUpd = new TMatchParticipant();
            slotUpd.setId(p.getId());
            slotUpd.setDisplaySlotIndex((long) (i + 1));
            participantMapper.updateById(slotUpd);
            List<TMatchRound> own = roundsByComp.getOrDefault(p.getCompetitorId(), List.of());
            if (!own.isEmpty()) {
                TMatchRound rUpd = new TMatchRound();
                rUpd.setId(own.get(0).getId());
                rUpd.setRoundSequence((long) (i + 1));
                matchRoundMapper.updateById(rUpd);
            }
        }
    }

    /** 持久化海选分圈方式(随机 true / 按号 false),保留 rule_config 中其余自定义字段 */
    private void persistCircleSplitMode(TStage stage, boolean randomSplit) {
        if (StringUtils.isBlank(stage.getRuleConfig())) {
            return;
        }
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
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

    /** 海选当前已生成的 ZONE 圈场次(按展示序) */
    private List<TMatch> auditionZoneMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .likeRight(TMatch::getDisplayZone, "ZONE-")
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId))
            .stream()
            // 同分加赛复用原圈 displayZone,不计入"圈场次"
            .filter(m -> !(StringUtils.isNotBlank(m.getRemark()) && m.getRemark().startsWith("同分加赛")))
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
                planned, auditionZoneMatches(stage.getId()).size());
            return;
        }
        List<TMatch> zones = auditionZoneMatches(stage.getId());
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
            m.setName("海选赛-" + c + "圈");
            m.setDisplayZone("ZONE-" + c);
            m.setDisplayRow((long) (c - 1));
            m.setDisplayCol(1L);
            m.setStatus(StageConstants.MATCH_PENDING);
            m.setMatchMode(matchMode);
            matchMapper.insert(m);
            log.info("海选赛段[{}]追加空白第{}圈(matchId={})", stage.getId(), c, m.getId());
        }
        // 按配置刷新圈-裁判绑定(新增圈一并绑定,已有圈幂等重绑)
        autoAssignCircleReferees(stage);
    }

    /**
     * 当前海选圈是否为「按号码顺序均分」:显式 randomSplit=false 或未记录(兼容旧数据)
     * 时按号落圈;randomSplit=true(随机抽取)时不按号。
     */
    private boolean isNumberSplitCircle(TStage stage, List<TMatch> matches) {
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode()) || matches.size() <= 1) {
            return false;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int circles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
        if (circles <= 1) {
            return false;
        }
        if (rc != null && Boolean.TRUE.equals(rc.getRandomSplit())) {
            return false;
        }
        if (rc != null && Boolean.FALSE.equals(rc.getRandomSplit())) {
            return true;
        }
        // 旧数据未记录分圈方式:按现有号码分布判断——圈间号码区间按圈序单调不交错即为按号分圈
        return circleNumbersOrderedByZone(matches, stage);
    }

    /**
     * 判断当前各圈号码是否按圈序连续递增(按号均分特征):
     * 每圈内号码升序,且下一圈最小号必须大于上一圈最大号。
     */
    private boolean circleNumbersOrderedByZone(List<TMatch> matches, TStage stage) {
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .select(TCompetitor::getId, TCompetitor::getNumber));
        Map<Long, Long> numByComp = new HashMap<>();
        for (TCompetitor c : comps) {
            int n = parseCompetitorNumber(c.getNumber());
            if (n != Integer.MAX_VALUE) {
                numByComp.put(c.getId(), (long) n);
            }
        }
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        if (matchIds.isEmpty()) {
            return false;
        }
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .isNotNull(TMatchParticipant::getCompetitorId));
        Map<Long, List<Long>> numsByMatch = new HashMap<>();
        for (TMatchParticipant p : parts) {
            Long num = numByComp.get(p.getCompetitorId());
            if (num != null) {
                numsByMatch.computeIfAbsent(p.getMatchId(), k -> new ArrayList<>()).add(num);
            }
        }
        long prevMax = Long.MIN_VALUE;
        for (TMatch m : matches) {
            List<Long> nums = numsByMatch.get(m.getId());
            if (nums == null || nums.isEmpty()) {
                continue;
            }
            Collections.sort(nums);
            if (nums.get(0) <= prevMax) {
                return false;
            }
            prevMax = nums.get(nums.size() - 1);
        }
        return true;
    }

    /**
     * 按号码均分时为新选手计算应落圈位:
     * 圈位由号码本身稳定决定(第 N 号 → 第 ((N-1) mod 圈数)+1 圈),
     * 不依赖已签到人数与签到顺序——「先空圈、边签到边抽号」时也能保证最终各圈均分。
     * 返回 null 表示无法按号定位(由调用方回退均衡)。
     */
    private TMatch pickCircleByNumberOrder(List<TMatch> matches, TStage stage, Long competitorId) {
        List<TCompetitor> actives = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode())
            .select(TCompetitor::getId, TCompetitor::getNumber));
        if (actives.isEmpty()) {
            return null;
        }
        int number = Integer.MAX_VALUE;
        for (TCompetitor c : actives) {
            if (Objects.equals(c.getId(), competitorId)) {
                number = parseCompetitorNumber(c.getNumber());
                break;
            }
        }
        if (number == Integer.MAX_VALUE) {
            return null;
        }
        if (matches.size() <= 1) {
            return null;
        }
        int circleIdx = (number - 1) % matches.size();
        if (circleIdx < 0 || circleIdx >= matches.size()) {
            return null;
        }
        return matches.get(circleIdx);
    }

    /**
     * 签到/补签到落圈策略:
     * <ul>
     *   <li>配置了 circleAdvanceCounts 时,按"每圈剩余名额余量"择优(余量=名额-当前人数,
     *       余量大的优先,兼顾每圈独立晋级名额),同余量取人数少者,再取圈序号小者;</li>
     *   <li>未配置每圈名额时退化为选当前人数最少的圈(保持各圈均衡)。</li>
     * </ul>
     */
    private TMatch pickCircleForCheckin(List<TMatch> candidates, TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        int uniformQuota = 0;
        if (explicitQuota) {
            int cfgCircles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
            uniformQuota = readStageAdvanceCount(stage) / Math.max(1, cfgCircles);
        }
        TMatch target = null;
        int bestRemaining = Integer.MIN_VALUE;
        long bestCount = Long.MAX_VALUE;
        long bestRow = Long.MAX_VALUE;
        for (TMatch m : candidates) {
            long cnt = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId()));
            int remaining = Integer.MIN_VALUE;
            if (explicitQuota) {
                int zoneIdx = zoneIndexOf(m.getDisplayZone());
                int quota = zoneIdx >= 0 && zoneIdx < perCircleCfg.size()
                    ? Math.max(0, perCircleCfg.get(zoneIdx)) : uniformQuota;
                remaining = quota - (int) Math.min(Integer.MAX_VALUE, cnt);
            }
            long row = m.getDisplayRow() != null ? m.getDisplayRow() : Long.MAX_VALUE;
            if (remaining > bestRemaining
                || (remaining == bestRemaining && (cnt < bestCount
                    || (cnt == bestCount && row < bestRow)))) {
                bestRemaining = remaining;
                bestCount = cnt;
                bestRow = row;
                target = m;
            }
        }
        return target;
    }

    /** 参赛号码转数值用于排序:非数字号码排最后 */
    private int parseCompetitorNumber(String number) {
        if (number == null || number.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
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

    /** displayZone("ZONE-1"..) -> 圈序号(0 基);"CENTER" 或无圈返回 0;解析失败返回 -1 */
    private int zoneIndexOf(String displayZone) {
        if (displayZone == null || displayZone.isBlank()) {
            return 0;
        }
        if (displayZone.startsWith("ZONE-")) {
            try {
                return Integer.parseInt(displayZone.substring(5)) - 1;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
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
        for (int i = 0; i < order.size(); i++) {
            TCompetitor upd = new TCompetitor();
            upd.setId(order.get(i));
            upd.setSeedRank((long) (i + 1));
            competitorMapper.updateById(upd);
        }
        log.info("赛段[{}]按外部抽签结果设定{}个参赛方种子顺序", stage.getId(), order.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return order.size();
    }

    /** 读取赛段晋级总数(海选/排名赛):顶层 advanceCount 优先,兼容旧版 knockout.advanceCount */
    private int readStageAdvanceCount(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int advanceCount = 1;
        if (rc != null && rc.getKnockout() != null && rc.getKnockout().getAdvanceCount() != null) {
            advanceCount = rc.getKnockout().getAdvanceCount();
        }
        try {
            Map<String, Object> raw = new tools.jackson.databind.ObjectMapper()
                .readValue(stage.getRuleConfig(), Map.class);
            if (raw != null && raw.containsKey("advanceCount")) {
                advanceCount = ((Number) raw.get("advanceCount")).intValue();
            }
        } catch (Exception ignored) {
        }
        return advanceCount;
    }

    /** 场次所属圈:displayZone 为空视为 CENTER */
    private String zoneOfMatch(TMatch m) {
        return m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
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

    /**
     * 随机分圈模式下把新签到选手移入目标圈(按计划圈序号 1..n):
     * 计划圈补建完成后调用,选手若被生成逻辑放入其他圈则先移出再挂入目标圈。
     */
    private void moveCompetitorToZoneByIndex(Long stageId, Long competitorId, Integer zoneIndex) {
        if (zoneIndex == null || zoneIndex <= 0) {
            return;
        }
        List<TMatch> zones = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .likeRight(TMatch::getDisplayZone, "ZONE-")
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (zones.size() < zoneIndex) {
            return;
        }
        TMatch target = zones.get(zoneIndex - 1);
        if (StageConstants.MATCH_SETTLED.equals(target.getStatus())
            || (StringUtils.isNotBlank(target.getRemark()) && target.getRemark().startsWith("同分加赛"))) {
            return;
        }
        List<TMatch> all = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        TMatch source = null;
        for (TMatch m : all) {
            long cnt = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .eq(TMatchParticipant::getCompetitorId, competitorId));
            if (cnt > 0) {
                source = m;
                break;
            }
        }
        if (source == null || source.getId().equals(target.getId())) {
            return;
        }
        removeParticipantWithRound(source, competitorId);
        appendParticipantWithRound(target, competitorId);
        tournamentEventNotifier.notify(zones.get(0).getTournamentId(),
            stageId, target.getId(), "stage");
        log.info("海选赛段[{}]按抽号选择把参赛方[{}]落入第{}圈", stageId, competitorId, zoneIndex);
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
        TStage prev = stage.getPrevStageId() != null ? stageMapper.selectById(stage.getPrevStageId()) : null;
        rc.getKnockout().setPairingMode(PairingModeResolver.resolve(
            null, seedsFromRanking(stage), PairingModeResolver.prevIsRanking(prev)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String completeStage(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("仅 GAMING 状态的赛段可完成,当前: {}", stage.getStatus());
        }
        // 擂台赛:对决逐场独立结算,校验无进行中对决后落库冠军/名次(决出闭环)
        if (StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            long gaming = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
            if (gaming > 0) {
                throw new ServiceException("仍有 {} 场对决进行中,请先完成或重启后再结束赛段", gaming);
            }
            settleArenaStage(stage);
        } else if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            // 海选赛:最终结算,聚合所有裁判打分并排名晋级。之后场次变 SETTLED
            // 二海(同分加赛)未判罚(仍有选手一条分都没打)时禁止结束赛段;
            // 弃权选手打 0 分(0 分不参与晋级)后即可正常结算
            assertTiebreakersJudged(stageId);
            settleAuditionStage(stage);
            // 检查是否还有加赛场次未完成
            long tbUnfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (tbUnfinished > 0) {
                // 海选出现二海(同分加赛):首次结算当场生成,赛段保持 GAMING,
                // 必须等裁判完成二海判罚后再次调用 completeStage 才能结束赛段。
                log.info("赛段[{}]存在{}场二海(同分加赛)未完成,赛段保持进行中,完成二海判罚后再结束",
                    stageId, tbUnfinished);
                return StageConstants.STAGE_GAMING;
            }
        } else if (StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            // 排名赛:最终结算,按多维度总分排名晋级。晋级线上同分并列者保持 PENDING,
            // 由导播台在中间态(下一赛段开始前)手动决定谁晋级/是否全部晋级
            settleRankStage(stage);
            long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (unfinished > 0) {
                log.info("赛段[{}]存在{}场未结算,等待全部结算后再次完成", stageId, unfinished);
                return StageConstants.STAGE_GAMING;
            }
        } else {
            // 多裁判累计打分场次(VOTING/RANKING):先统一结算,再校验是否全部完成
            scoredMatchService.settleScoredMatches(stageId);
            long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (unfinished > 0) {
                throw new ServiceException("赛段仍有 {} 场未结算,无法完成", unfinished);
            }
        }
        // 小组赛:按组累计积分排名,前 advancePerGroup 名晋级
        if (StageModeEnum.GROUP.getCode().equals(stage.getStageMode())) {
            settleGroupStage(stage);
        }
        stage.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(stage);
        // 名单就绪度由源结算状态推导,结算完成无需推进任何状态;
        // 下游开赛守卫与 apply 都会现场按源状态计算。
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        // 不再由后端自动确认晋级:完成赛段仅产出晋级预排;是否跳过中间态由 MC 导播台
        // 在开始下一赛段时弹窗确认后调用「确认晋级」接口决定(见 isAutoConfirmAdvancement)
        return StageConstants.STAGE_SETTLED;
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
        List<List<Object>> rows = new ArrayList<>();
        for (AuditionResultVo.CompetitorItem c : result.getCompetitors()) {
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

        try {
            org.dromara.common.core.utils.file.FileUtils.setAttachmentResponseHeader(
                response, "海选结果-" + stage.getName() + ".xlsx");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8");
            try (jakarta.servlet.ServletOutputStream os = response.getOutputStream()) {
                ExcelWriter writer = cn.idev.excel.FastExcel.write(os).build();
                try {
                    WriteSheet mainSheet = cn.idev.excel.FastExcel.writerSheet("海选成绩").head(head).build();
                    writer.write(rows, mainSheet);
                    // 二海/三海…:多圈时每圈独立一张 sheet,避免不同圈的加赛选手混在一起
                    java.util.Set<String> usedSheetNames = new java.util.HashSet<>();
                    for (AuditionResultVo.TiebreakerItem tb : result.getTiebreakers()) {
                        List<List<Object>> tbRows = new ArrayList<>();
                        for (AuditionResultVo.CompetitorItem c : tb.getCompetitors()) {
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
                        WriteSheet tbSheet = cn.idev.excel.FastExcel.writerSheet(sheetName).head(tbHead).build();
                        writer.write(tbRows, tbSheet);
                    }
                } finally {
                    writer.finish();
                }
            }
        } catch (java.io.IOException e) {
            throw new ServiceException("导出海选结果失败: {}", e.getMessage());
        }
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
        List<TMatch> zones = auditionZoneMatches(stage.getId());
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
            if (StringUtils.isNotBlank(m.getRemark()) && m.getRemark().startsWith("同分加赛")) {
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
            String zone = zoneOfMatch(m);
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

    /**
     * 二海(同分加赛)守卫:加赛里还有选手未打分(未判罚)时,禁止结束赛段。
     * 弃权按「打 0 分」处理(0 分不参与晋级),全部加赛选手有成绩记录后即可结算。
     */
    private void assertTiebreakersJudged(Long stageId) {
        List<TMatch> tiebreakers = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .likeRight(TMatch::getRemark, "同分加赛")
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (tiebreakers.isEmpty()) {
            return;
        }
        List<Long> tbIds = tiebreakers.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> tbParts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, tbIds)
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (tbParts.isEmpty()) {
            return;
        }
        List<Long> compIds = tbParts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        List<Long> tbRoundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, tbIds)
                    .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Set<Long> judged = tbRoundIds.isEmpty() ? Set.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, tbRoundIds)
                    .select(TRoundScore::getCompetitorId))
                .stream()
                .map(TRoundScore::getCompetitorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> unjudged = new ArrayList<>();
        for (TMatchParticipant p : tbParts) {
            if (p.getCompetitorId() == null || judged.contains(p.getCompetitorId())) {
                continue;
            }
            TCompetitor c = compMap.get(p.getCompetitorId());
            // 已标记退赛(WITHDRAWN)的选手不参与判罚,不算未判罚
            if (c != null && OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            unjudged.add(c != null ? c.getName() : ("选手" + p.getCompetitorId()));
        }
        if (!unjudged.isEmpty()) {
            throw new ServiceException("海选存在二海(同分加赛)未完成判罚: {},请完成二海判罚后再结束赛段(弃权选手打 0 分,0 分不参与晋级)",
                String.join(", ", unjudged));
        }
    }

    /**
     * 擂台赛结算:把轮转队列首位落库为冠军(ADVANCE/finalRank=1),其余按胜场积分
     * 排 2..n 并标记淘汰,使擂台赛结果可被查询、可接续下一赛段晋级。
     * 至少完成一场对决才允许结束,避免空擂台直接"决出冠军"。
     */
    private void settleArenaStage(TStage stage) {
        List<Long> queue = computeArenaQueue(stage.getId());
        if (queue.isEmpty()) {
            throw new ServiceException("擂台赛无参赛者,无法完成赛段");
        }
        long battles = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (battles == 0) {
            throw new ServiceException("擂台赛尚未进行任何对决,无法完成赛段(请先创建并完成至少一场对决)");
        }
        Map<Long, Integer> points = arenaPoints(stage.getId());
        Long championId = queue.get(0);
        List<Long> rest = new ArrayList<>(queue.subList(1, queue.size()));
        // 亚军及以后按胜场积分降序;积分相同按参赛方 ID 稳定排序
        rest.sort((a, b) -> {
            int cmp = Integer.compare(points.getOrDefault(b, 0), points.getOrDefault(a, 0));
            return cmp != 0 ? cmp : Long.compare(a, b);
        });
        TCompetitor cupd = new TCompetitor();
        cupd.setId(championId);
        cupd.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        cupd.setFinalRank(1L);
        competitorMapper.updateById(cupd);
        long rank = 2L;
        for (Long cid : rest) {
            TCompetitor upd = new TCompetitor();
            upd.setId(cid);
            upd.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
            upd.setFinalRank(rank++);
            competitorMapper.updateById(upd);
        }
        log.info("擂台赛[{}]完成:冠军[{}]({}胜),共{}场对决,{}名参赛者落库排名", stage.getId(),
            championId, points.getOrDefault(championId, 0), battles, queue.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetStageToDraft(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())
            && !StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            throw new ServiceException("仅 DRAFT/PENDING 状态的赛段可重置为草稿,当前: {}", stage.getStatus());
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

    /** 沿 prev 链从第一个淘汰赛赛段开始计数,返回当前赛段的轮次序号(16强=1、8强=2、半决赛=3、决赛=4) */
    private int knockoutRoundNo(TStage stage) {
        List<TStage> chain = new ArrayList<>();
        TStage cur = stage;
        Set<Long> seen = new HashSet<>();
        while (cur != null && seen.add(cur.getId())) {
            chain.add(cur);
            cur = cur.getPrevStageId() != null ? stageMapper.selectById(cur.getPrevStageId()) : null;
        }
        Collections.reverse(chain);
        int round = 0;
        for (TStage s : chain) {
            if (StageModeEnum.KNOCKOUT.getCode().equals(s.getStageMode())) {
                round++;
            }
            if (s.getId().equals(stage.getId())) {
                return round;
            }
        }
        return 1;
    }

    private Long resolveNextStageId(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc != null && rc.getTransition() != null && rc.getTransition().getTargetStageId() != null) {
            return rc.getTransition().getTargetStageId();
        }
        return stage.getNextStageId();
    }

    /** 上一赛段:优先 prevStageId,缺失时按 nextStageId 反向反查(兼容只维护单向链的赛段) */
    private TStage resolvePrevStage(TStage stage) {
        if (stage.getPrevStageId() != null) {
            TStage prev = stageMapper.selectById(stage.getPrevStageId());
            if (prev != null) {
                return prev;
            }
            // prevStageId 悬空(指向已删除赛段)时,按 nextStageId 反向反查兜底,
            // 避免「上一赛段不存在」阻断本可自愈的链表
            return stageMapper.selectOne(Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, stage.getTournamentId())
                .eq(TStage::getNextStageId, stage.getId())
                .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
                .last("LIMIT 1"));
        }
        return stageMapper.selectOne(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, stage.getTournamentId())
            .eq(TStage::getNextStageId, stage.getId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .last("LIMIT 1"));
    }

    /**
     * 小组赛结算:按组(displayZone)累计参赛方胜负积分 → 组内排名 →
     * 前 advancePerGroup 名 outcomeStatus=ADVANCE 并写 finalRank,其余 ELIMINATED。
     */
    private void settleGroupStage(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        GroupConfig gc = rc != null ? rc.getGroup() : null;
        int winP = (gc != null && gc.getWinPoints() != null) ? gc.getWinPoints() : 3;
        int drawP = (gc != null && gc.getDrawPoints() != null) ? gc.getDrawPoints() : 1;
        int lossP = (gc != null && gc.getLossPoints() != null) ? gc.getLossPoints() : 0;
        int advancePerGroup = (gc != null && gc.getAdvancePerGroup() != null) ? gc.getAdvancePerGroup() : 1;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
        if (matches.isEmpty()) {
            return;
        }

        // 按 displayZone(组)分组 matchId
        Map<String, List<Long>> groupMatchIds = matches.stream()
            .filter(m -> m.getDisplayZone() != null)
            .collect(Collectors.groupingBy(TMatch::getDisplayZone,
                Collectors.mapping(TMatch::getId, Collectors.toList())));

        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
        List<Long> compIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        for (Map.Entry<String, List<Long>> entry : groupMatchIds.entrySet()) {
            String zone = entry.getKey();
            List<Long> gMatchIds = entry.getValue();
            java.util.Set<Long> gSet = new java.util.HashSet<>(gMatchIds);
            // 组内参赛方(退赛除外)
            java.util.Set<Long> memberSet = new java.util.HashSet<>();
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() != null && gSet.contains(p.getMatchId())) {
                    TCompetitor c = compMap.get(p.getCompetitorId());
                    if (c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                        memberSet.add(p.getCompetitorId());
                    }
                }
            }
            List<Long> members = new ArrayList<>(memberSet);
            // competitorId -> 累计积分
            Map<Long, Integer> points = new HashMap<>();
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() == null || !gSet.contains(p.getMatchId())
                    || !members.contains(p.getCompetitorId())) {
                    continue;
                }
                String o = p.getOutcomeStatus();
                int pt;
                if (MatchOutcomeEnum.WIN.getCode().equals(o)) {
                    pt = winP;
                } else if (MatchOutcomeEnum.DRAW.getCode().equals(o)) {
                    pt = drawP;
                } else if (MatchOutcomeEnum.LOSS.getCode().equals(o)) {
                    pt = lossP;
                } else {
                    continue;
                }
                points.merge(p.getCompetitorId(), pt, Integer::sum);
            }

            // 组内按积分降序排名
            List<Long> scored = members.stream()
                .filter(points::containsKey)
                .sorted((a, b) -> {
                    int cmp = Integer.compare(points.get(b), points.get(a));
                    return cmp != 0 ? cmp : Long.compare(a, b);
                })
                .collect(Collectors.toList());
            int n = scored.size();
            int rankCursor = 1;
            // 晋级线同分并列:同分者保持 PENDING,由导播台在中间态用 adjustAdvancement 定夺
            java.util.Set<Long> pendingSet = new java.util.HashSet<>();
            if (advancePerGroup < n) {
                int cutoff = points.get(scored.get(advancePerGroup - 1));
                List<Long> tied = scored.stream()
                    .filter(c -> points.get(c) == cutoff).collect(Collectors.toList());
                int firstTied = scored.indexOf(tied.get(0));
                int lastTied = scored.indexOf(tied.get(tied.size() - 1));
                if (tied.size() > 1 && lastTied >= advancePerGroup) {
                    for (int i = 0; i < firstTied; i++) {
                        markGroupResult(scored.get(i), OutcomeStatusEnum.ADVANCE.getCode(), (long) (i + 1), stage.getId());
                    }
                    for (int i = lastTied + 1; i < n; i++) {
                        markGroupResult(scored.get(i), OutcomeStatusEnum.ELIMINATED.getCode(), (long) (i + 1), stage.getId());
                    }
                    pendingSet.addAll(tied);
                    rankCursor = n + 1;
                    log.info("组[{}]晋级线出现{}名同分并列,保持待定等待导播台调整", zone, tied.size());
                }
            }
            if (pendingSet.isEmpty()) {
                for (int i = 0; i < n; i++) {
                    boolean advance = i < advancePerGroup;
                    markGroupResult(scored.get(i),
                        advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (i + 1), stage.getId());
                }
                rankCursor = n + 1;
            }
            // 未出场/未获分者(退赛除外)标记淘汰,排名顺延
            for (Long cid : members) {
                if (!points.containsKey(cid)) {
                    markGroupResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(), (long) rankCursor++, stage.getId());
                }
            }
        }
    }

    /** 回写小组赛参赛者赛段级结果 */
    private void markGroupResult(Long competitorId, String outcome, Long finalRank, Long stageId) {
        TCompetitor cupd = new TCompetitor();
        cupd.setId(competitorId);
        cupd.setFinalRank(finalRank);
        cupd.setOutcomeStatus(outcome);
        competitorMapper.updateById(cupd);
    }

    /**
     * 海选赛结算:取赛场所有参赛方的当前总分(由多裁判累计提交后写在 participant.scoreValue),
     * 按分数降序排名,前 advanceCount 名标 ADVANCE,其余标 ELIMINATED。
     */
    private void settleAuditionStage(TStage stage) {
        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }
        // 圈名额/全局排名起点统一计算(与二海/三海单场自动结算共用,避免口径分叉)
        Map<String, int[]> zoneCtx = buildAuditionZoneContext(stage, matches);
        // 圈内已晋级数(含已结算正式圈与加赛,支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countAuditionAdvancedByZone(stage, matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
            int[] qb = zoneCtx.get(zone);
            if (qb == null) {
                continue;
            }
            settleAuditionMatch(match, qb[0], qb[1], zoneAdvanced);
        }
    }

    /**
     * 海选圈上下文统一计算:每圈晋级名额 + 全局排名起点(按场次 displayRow 顺序)。
     * 整段结算与二海/三海单场自动结算共用,保证每圈名额与排名起点口径一致。
     *
     * @return zone(CENTER 归一) -> [每圈晋级名额, 全局排名起点]
     */
    private Map<String, int[]> buildAuditionZoneContext(TStage stage, List<TMatch> matches) {
        int advanceCount = readStageAdvanceCount(stage);
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        if (explicitQuota) {
            for (Integer q : perCircleCfg) {
                if (q == null || q < 0) {
                    throw new ServiceException("每圈晋级人数配置非法(不能为负): {}", perCircleCfg);
                }
            }
        }
        // 圈数以实际生成的场次为准:配置圈数可能被生成器按人数收缩(人数<圈数),
        // 也可能在生成后被修改,按配置结算会导致名额均分错位或整除校验误报
        int circles = Math.max(1, (int) matches.stream()
            .map(m -> m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone())
            .distinct().count());
        int plannedCircles = rc != null && rc.getCircles() != null ? Math.max(1, rc.getCircles()) : circles;
        // 历史残留的"配置圈数之外"场次按 0 人晋级处理;正常名额按配置圈数均分
        int divideBy = circles > plannedCircles ? plannedCircles : circles;
        int perCircle = divideBy > 1 ? advanceCount / divideBy : advanceCount;
        // 未显式配置每圈名额时才要求均分可整除
        if (!explicitQuota && circles <= plannedCircles && advanceCount > 0 && advanceCount % circles != 0) {
            throw new ServiceException("海选总晋级数[{}]无法按实际[{}]圈均分,请调整晋级名额或圈数", advanceCount, circles);
        }

        Map<String, int[]> ctx = new HashMap<>();
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : matches) {
            String zone = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            if (ctx.containsKey(zone)) {
                continue;
            }
            int quota = ordinal >= plannedCircles ? 0
                : explicitQuota && ordinal < perCircleCfg.size()
                    ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
            ctx.put(zone, new int[]{quota, acc});
            acc += quota;
            ordinal++;
        }
        return ctx;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAutoSettleTiebreaker(Long matchId) {
        if (matchId == null) {
            return false;
        }
        TMatch match = matchMapper.selectById(matchId);
        if (match == null || !StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            return false;
        }
        boolean tiebreaker = StringUtils.isNotBlank(match.getRemark())
            && match.getRemark().startsWith("同分加赛");
        if (!tiebreaker) {
            return false;
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage == null || !StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            return false;
        }
        // 全员已打分(任一裁判打过即可,与 assertTiebreakersJudged 同口径);退赛选手不计
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            return false;
        }
        List<Long> cids = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> compById = cids.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(cids).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Set<Long> judged = roundIds.isEmpty() ? Set.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds).select(TRoundScore::getCompetitorId))
                .stream().map(TRoundScore::getCompetitorId).filter(Objects::nonNull).collect(Collectors.toSet());
        for (TMatchParticipant p : parts) {
            TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
            if (c != null && OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            if (p.getCompetitorId() == null || !judged.contains(p.getCompetitorId())) {
                return false;
            }
        }
        // 单场结算:圈名额/全局排名起点与整段结算共用同一计算,幂等只处理未结算场次
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        Map<String, int[]> zoneCtx = buildAuditionZoneContext(stage, matches);
        Map<String, Integer> zoneAdvanced = countAuditionAdvancedByZone(stage, matches);
        String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
        int[] qb = zoneCtx.get(zone);
        if (qb == null) {
            return false;
        }
        settleAuditionMatch(match, qb[0], qb[1], zoneAdvanced);
        log.info("海选加赛[{}]全员打分完成,已自动结算", match.getId());
        refereeSseNotifier.notifyMatch(stage.getId(), match.getId(), "match");
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), match.getId(), "stage");
        return true;
    }

    @Override
    public void setMatchCurrentCompetitor(Long matchId, Long competitorId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (competitorId == null) {
            matchCurrentCompetitor.remove(matchId);
        } else {
            matchCurrentCompetitor.put(matchId, competitorId);
        }
        // 广播:大屏 widget 与导播台按事件刷新当前标记
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "stage");
    }

    @Override
    public Long getMatchCurrentCompetitor(Long matchId) {
        return matchCurrentCompetitor.get(matchId);
    }

    /**
     * 统计海选各圈(displayZone)已晋级人数,每圈独立结算时用它计算剩余名额。
     */
    private Map<String, Integer> countAuditionAdvancedByZone(TStage stage, List<TMatch> matches) {
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : matches) {
            matchZone.put(m.getId(), m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone());
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchZone.keySet())
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        Map<String, Integer> result = new HashMap<>();
        for (TMatchParticipant p : parts) {
            result.merge(matchZone.getOrDefault(p.getMatchId(), "CENTER"), 1, Integer::sum);
        }
        return result;
    }

    private void settleAuditionMatch(TMatch match, int advanceQuota,
                                     int zoneBase,
                                     Map<String, Integer> zoneAdvanced) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId())
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            TMatch mUpd = new TMatch();
            mUpd.setId(match.getId());
            mUpd.setStatus(StageConstants.MATCH_SETTLED);
            matchMapper.updateById(mUpd);
            return;
        }
        List<Long> partIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();
        Map<Long, TCompetitor> compMap = partIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(partIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        // 退赛选手不参与排名、不占用晋级名额(保持 WITHDRAWN)
        List<TMatchParticipant> active = parts.stream()
            .filter(p -> p.getCompetitorId() != null)
            .filter(p -> {
                TCompetitor c = compMap.get(p.getCompetitorId());
                return c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
            })
            .toList();
        if (active.isEmpty()) {
            // 圈内全员退赛:直接置为已结算,不产出晋级者
            TMatch mUpd = new TMatch();
            mUpd.setId(match.getId());
            mUpd.setStatus(StageConstants.MATCH_SETTLED);
            matchMapper.updateById(mUpd);
            return;
        }
        // 剩余晋级名额:按圈独立计算,加赛场次只争本圈尚未确定的晋级位
        String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);

        Map<Long, java.math.BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : active) {
            if (p.getCompetitorId() != null) {
                scores.put(p.getCompetitorId(), p.getScoreValue() != null ? p.getScoreValue() : java.math.BigDecimal.ZERO);
            }
        }
        List<Long> sortedCids = new ArrayList<>(scores.keySet());
        sortedCids.sort((a, b) -> {
            java.math.BigDecimal sa = scores.getOrDefault(a, java.math.BigDecimal.ZERO);
            java.math.BigDecimal sb = scores.getOrDefault(b, java.math.BigDecimal.ZERO);
            int cmp = sb.compareTo(sa);
            if (cmp != 0) {
                return cmp;
            }
            // 非晋级线的同分:按签到时抽签的号码牌升序定先后(名次唯一,不并列)
            TCompetitor ca = compMap.get(a);
            TCompetitor cb = compMap.get(b);
            int na = ca == null ? Integer.MAX_VALUE : parseCompetitorNumber(ca.getNumber());
            int nb = cb == null ? Integer.MAX_VALUE : parseCompetitorNumber(cb.getNumber());
            return Integer.compare(na, nb);
        });
        // 名次 = 上面的排序位次(分数降序、同分按号码牌);晋级线上的并列由二海决出后再回写名次
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sortedCids.size(); i++) {
            ranks.put(sortedCids.get(i), i + 1);
        }

        // 0 分选手(弃权/缺席,含未打分)不参与晋级,也不参与同分加赛;
        // 正分人数不足晋级名额时,剩余名额空缺(下一赛段对应位置轮空)
        int positiveCount = 0;
        for (Long cid : sortedCids) {
            if (scores.get(cid).compareTo(java.math.BigDecimal.ZERO) > 0) {
                positiveCount++;
            }
        }

        // 名次段加赛(名次线上并列、双方结果早已确定):只决先后,不改晋级/淘汰结果
        Map<Long, String> keepOutcome = new HashMap<>();
        boolean rankTiebreak = StringUtils.isNotBlank(match.getRemark())
            && match.getRemark().startsWith("同分加赛")
            && match.getRemark().contains("名次段");
        if (rankTiebreak) {
            for (TCompetitor c : compMap.values()) {
                if (c.getOutcomeStatus() != null
                    && !OutcomeStatusEnum.PENDING.getCode().equals(c.getOutcomeStatus())) {
                    keepOutcome.put(c.getId(), c.getOutcomeStatus());
                }
            }
        }

        // 剩余名额已满,本场(加赛)所有人淘汰
        if (remaining <= 0) {
            for (int i = 0; i < sortedCids.size(); i++) {
                markAuditionResult(sortedCids.get(i),
                    keepOutcome.getOrDefault(sortedCids.get(i), OutcomeStatusEnum.ELIMINATED.getCode()),
                    (long) (zoneBase + i + 1 + alreadyAdvanced), ranks, match.getId());
            }
            TMatch mUpd = new TMatch();
            mUpd.setId(match.getId());
            mUpd.setStatus(StageConstants.MATCH_SETTLED);
            matchMapper.updateById(mUpd);
            log.info("海选赛场次[{}]结算:晋级名额已满,{}名同分选手淘汰", match.getId(), sortedCids.size());
            return;
        }

        // 检测晋级线上的同分情况(仅正分选手参与;正分人数不足名额时直接晋级,不产生加赛)
        if (remaining < positiveCount) {
            java.math.BigDecimal cutoffScore = scores.get(sortedCids.get(remaining - 1));
            // 统计与 cutoffScore 同分的所有选手
            List<Long> tiedAtCutoff = new ArrayList<>();
            for (Long cid : sortedCids) {
                if (scores.get(cid).compareTo(cutoffScore) == 0) {
                    tiedAtCutoff.add(cid);
                }
            }
            // 同分导致晋级人数超限,需要加赛
            if (tiedAtCutoff.size() > 1 && remaining <= sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1))) {
                // 明确晋级者:排在 tiedAtCutoff 中第一名之前的所有人
                int firstTiedPos = sortedCids.indexOf(tiedAtCutoff.get(0));
                for (int i = 0; i < firstTiedPos; i++) {
                    Long cid = sortedCids.get(i);
                    markAuditionResult(cid, OutcomeStatusEnum.ADVANCE.getCode(),
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
                }
                // 明确淘汰者:排在 tiedAtCutoff 中最后一名之后的所有人
                int lastTiedPos = sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1));
                for (int i = lastTiedPos + 1; i < sortedCids.size(); i++) {
                    Long cid = sortedCids.get(i);
                    markAuditionResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (zoneBase + i + 1 + alreadyAdvanced), ranks, match.getId());
                }
                zoneAdvanced.put(zone, alreadyAdvanced + firstTiedPos);
                // 同分选手保持 PENDING,创建加赛场次
                createTiebreakerMatch(match, tiedAtCutoff);
                TMatch mUpd = new TMatch();
                mUpd.setId(match.getId());
                mUpd.setStatus(StageConstants.MATCH_SETTLED);
                matchMapper.updateById(mUpd);
                log.info("海选赛场次[{}]出现{}名同分选手,已创建加赛", match.getId(), tiedAtCutoff.size());
                return;
            }
        }

        // 正常结算:0 分选手永不晋级;正分选手按分数从高到低取前 min(remaining, positiveCount) 名,
        // 名额不足时剩余名额空缺(下一赛段轮空)
        int advanced = 0;
        for (int i = 0; i < sortedCids.size(); i++) {
            Long cid = sortedCids.get(i);
            boolean eligible = scores.get(cid).compareTo(java.math.BigDecimal.ZERO) > 0;
            boolean advance = eligible && advanced < remaining;
            if (advance) {
                advanced++;
            }
            String computed = advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode();
            markAuditionResult(cid, keepOutcome.getOrDefault(cid, computed),
                (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
        }
        zoneAdvanced.put(zone, alreadyAdvanced + advanced);

        TMatch mUpd = new TMatch();
        mUpd.setId(match.getId());
        mUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchMapper.updateById(mUpd);

        log.info("海选赛场次[{}]已结算,共{}名选手,正分{}名,晋级{}名(0分选手不晋级)", match.getId(),
            sortedCids.size(), positiveCount, advanced);
    }

    private void markAuditionResult(Long cid, String outcome, Long finalRank,
                                     Map<Long, Integer> ranks, Long matchId) {
        TCompetitor cupd = new TCompetitor();
        cupd.setId(cid);
        cupd.setFinalRank(finalRank);
        cupd.setOutcomeStatus(outcome);
        competitorMapper.updateById(cupd);

        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setRankInMatch(ranks.get(cid) != null ? ranks.get(cid).longValue() : null);
        pUpd.setOutcomeStatus(outcome);
        participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, matchId)
            .eq(TMatchParticipant::getCompetitorId, cid));

        TMatch match = matchMapper.selectById(matchId);
        // 二海(同分加赛)结算:把晋级/淘汰结果同步回该选手在所有场次(原始海选场、中间加赛场)的
        // 参赛方行,避免原始场次参赛方状态停留在 PENDING 而一直显示"进行中";
        // 普通海选场每个选手只有一行,无需跨场次同步
        boolean tiebreaker = match != null && StringUtils.isNotBlank(match.getRemark())
            && match.getRemark().startsWith("同分加赛");
        if (tiebreaker) {
            List<Long> stageMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, match.getStageId())
                    .select(TMatch::getId))
                .stream().map(TMatch::getId).toList();
            if (!stageMatchIds.isEmpty()) {
                TMatchParticipant sync = new TMatchParticipant();
                sync.setOutcomeStatus(outcome);
                participantMapper.update(sync, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .in(TMatchParticipant::getMatchId, stageMatchIds)
                    .eq(TMatchParticipant::getCompetitorId, cid));
            }
            // 二海/三海结果回写原始海选场的本场排名(按最终排名顺序重排同分小组)
            syncTiebreakerOriginalRank(match);
        }
    }

    /**
     * 二海/三海结算后,把同分小组在原始海选场(一海)的本场排名写回:
     * 小组基准名次 = 原场竞争性排名(同分并列时的名次),按各成员最终排名(finalRank,
     * 即加赛逐级决出的顺序)依次顺延;尚未出结果(PENDING)的成员保持不动。
     */
    private void syncTiebreakerOriginalRank(TMatch tbMatch) {
        if (tbMatch == null || tbMatch.getStageId() == null) {
            return;
        }
        // 找原始海选场:同赛段同圈、非加赛、displayRow 小于加赛场次的最近一场(加赛链逐级回退)
        List<TMatch> candidates = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, tbMatch.getStageId())
            .eq(tbMatch.getDisplayZone() != null, TMatch::getDisplayZone, tbMatch.getDisplayZone())
            .isNull(tbMatch.getDisplayZone() == null, TMatch::getDisplayZone)
            .orderByDesc(TMatch::getDisplayRow));
        TMatch original = null;
        for (TMatch m : candidates) {
            boolean tiebreaker = StringUtils.isNotBlank(m.getRemark()) && m.getRemark().startsWith("同分加赛");
            if (tiebreaker) {
                continue;
            }
            if (m.getDisplayRow() != null && tbMatch.getDisplayRow() != null
                && m.getDisplayRow() < tbMatch.getDisplayRow()) {
                original = m;
                break;
            }
        }
        if (original == null) {
            return;
        }
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, original.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            return;
        }
        // 原场竞争性排名(同分并列),用于确定同分小组的基准名次
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : parts) {
            scores.put(p.getCompetitorId(), p.getScoreValue() != null ? p.getScoreValue() : BigDecimal.ZERO);
        }
        Map<Long, Integer> compRanks = com.dance.street.game.engine.scoring.RankCalculator.rank(scores);
        // 同分小组 = 本加赛场次的参与方
        List<TMatchParticipant> tbParts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, tbMatch.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        List<Long> groupIds = tbParts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        if (groupIds.isEmpty()) {
            return;
        }
        Integer baseRank = groupIds.stream()
            .map(compRanks::get).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        if (baseRank == null) {
            return;
        }
        // 连环加赛未全部决出时先不回写(避免中间名次错误),等全组有最终排名后再统一写回
        List<TCompetitor> comps = competitorMapper.selectByIds(groupIds);
        Map<Long, TCompetitor> compById = comps.stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        boolean anyUnresolved = false;
        for (Long gid : groupIds) {
            TCompetitor c = compById.get(gid);
            if (c == null || OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            if (c.getFinalRank() == null) {
                anyUnresolved = true;
                break;
            }
        }
        if (anyUnresolved) {
            return;
        }
        // 已出结果(有最终排名)的成员按 finalRank 升序,依次写回 base, base+1, …
        List<TCompetitor> resolved = groupIds.stream()
            .map(compById::get).filter(Objects::nonNull)
            .filter(c -> !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus()))
            .filter(c -> c.getFinalRank() != null)
            .sorted(Comparator.comparing(TCompetitor::getFinalRank))
            .toList();
        for (int i = 0; i < resolved.size(); i++) {
            TCompetitor c = resolved.get(i);
            TMatchParticipant upd = new TMatchParticipant();
            upd.setRankInMatch(baseRank.longValue() + i);
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, original.getId())
                .eq(TMatchParticipant::getCompetitorId, c.getId()));
        }
    }

    /**
     * 创建加赛场次:只有同分选手参与,胜负决出后由 completeStage 再次结算。
     */
    private void createTiebreakerMatch(TMatch parentMatch, List<Long> tiedCompetitorIds) {
        createTiebreakerMatch(parentMatch, tiedCompetitorIds, "晋级名额");
    }

    /**
     * 创建加赛场次(同 stage 内的新 match,由它决定原场中悬而未决的名次)。
     *
     * @param tag 这条边界的人话标签(如"晋级名额""名次段第24名"),写入 remark:
     *            既用于导出分组,也用于「同一边界最多加赛几轮」的计数
     */
    private void createTiebreakerMatch(TMatch parentMatch, List<Long> tiedCompetitorIds, String tag) {
        // 同一圈 + 同一边界最多加赛 4 轮(二海~五海):仍决不出就人工裁决/重置,
        // 避免操作失误导致加赛场次无限堆积、赛段永远无法完成
        long tiebreakerCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, parentMatch.getStageId())
            .eq(parentMatch.getDisplayZone() != null, TMatch::getDisplayZone, parentMatch.getDisplayZone())
            .likeRight(TMatch::getRemark, "同分加赛")
            .like(TMatch::getRemark, tag));
        if (tiebreakerCount >= 4) {
            throw new ServiceException(
                "海选「{}」已连续 {} 轮同分加赛仍未决出,请人工裁决(如调整打分或重置赛段后重排)",
                tag, tiebreakerCount);
        }
        TMatch tb = new TMatch();
        tb.setTournamentId(parentMatch.getTournamentId());
        tb.setStageId(parentMatch.getStageId());
        tb.setName(parentMatch.getName() + "-加赛");
        tb.setStatus(StageConstants.MATCH_PENDING);
        tb.setMatchMode(parentMatch.getMatchMode());
        // 加赛归属原圈(同 zone),结算时只争本圈剩余名额
        tb.setDisplayZone(parentMatch.getDisplayZone());
        tb.setDisplayRow(parentMatch.getDisplayRow() != null ? parentMatch.getDisplayRow() + 1L : 1L);
        tb.setDisplayCol(2L);
        tb.setRemark("同分加赛," + tag + "," + tiedCompetitorIds.size() + "人");
        matchMapper.insert(tb);

        // 一个轮次
        TMatchRound round = new TMatchRound();
        round.setTournamentId(tb.getTournamentId());
        round.setMatchId(tb.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_PENDING);
        matchRoundMapper.insert(round);

        // 每个同分选手一个参赛位
        for (int i = 0; i < tiedCompetitorIds.size(); i++) {
            TMatchParticipant p = new TMatchParticipant();
            p.setTournamentId(tb.getTournamentId());
            p.setMatchId(tb.getId());
            p.setCompetitorId(tiedCompetitorIds.get(i));
            p.setDisplaySlotIndex((long) (i + 1));
            p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
            participantMapper.insert(p);
        }

        // 自动开始加赛
        tb.setStatus(StageConstants.MATCH_GAMING);
        matchMapper.updateById(tb);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.updateById(round);
        // 加赛创建后必须推送:裁判端需要看到新场次才能打分,导播端需要知道赛段尚未完成
        refereeSseNotifier.notifyMatch(tb.getStageId(), tb.getId(), "match");
        tournamentEventNotifier.notify(tb.getTournamentId(), tb.getStageId(), tb.getId(), "match");

        log.info("加赛场次[{}]已创建并自动开始,{}名选手参与", tb.getId(), tiedCompetitorIds.size());
    }

    /**
     * 排名赛结算:按圈聚合全部裁判×维度打分,计算每圈总分排名,
     * 晋级线内正常晋级;晋级线上同分并列导致名额超限时,同分者保持 PENDING,
     * 由导播台在中间态手动指定晋级者(adjustAdvancement)。
     */
    private void settleRankStage(TStage stage) {
        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }
        int advanceCount = readStageAdvanceCount(stage);
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        if (explicitQuota) {
            for (Integer q : perCircleCfg) {
                if (q == null || q < 0) {
                    throw new ServiceException("每圈晋级人数配置非法(不能为负): {}", perCircleCfg);
                }
            }
        }
        // 圈数以实际生成的场次为准(配置圈数可能被生成器收缩,或生成后被修改)
        int circles = (int) matches.stream()
            .map(m -> m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone())
            .distinct().count();
        circles = Math.max(1, circles);
        int plannedCircles = rc != null && rc.getCircles() != null ? Math.max(1, rc.getCircles()) : circles;
        // 历史残留的"配置圈数之外"场次按 0 人晋级处理;正常名额按配置圈数均分
        int divideBy = circles > plannedCircles ? plannedCircles : circles;
        int perCircle = divideBy > 1 ? advanceCount / divideBy : advanceCount;
        if (!explicitQuota && circles <= plannedCircles && advanceCount > 0 && advanceCount % circles != 0) {
            throw new ServiceException("排名赛总晋级数[{}]无法按实际[{}]圈均分,请调整晋级名额或圈数", advanceCount, circles);
        }

        // 圈序号 + 每圈晋级名额 + 全局排名起点(前序各圈名额累加)
        List<String> orderedZones = new ArrayList<>();
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneQuota = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : matches) {
            String zone = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            if (zoneOrdinal.putIfAbsent(zone, ordinal) == null) {
                int quota = ordinal >= plannedCircles ? 0
                    : explicitQuota && ordinal < perCircleCfg.size()
                        ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
                zoneQuota.put(zone, quota);
                zoneBase.put(zone, acc);
                acc += quota;
                orderedZones.add(zone);
                ordinal++;
            }
        }
        // 圈内已晋级数(支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countRankAdvancedByZone(stage, matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
            settleRankMatch(match, rc, zoneQuota.getOrDefault(zone, perCircle),
                zoneBase.getOrDefault(zone, 0), zoneAdvanced);
        }
    }

    /** 统计排名赛各圈(displayZone)已晋级人数,每圈独立结算时用它计算剩余名额 */
    private Map<String, Integer> countRankAdvancedByZone(TStage stage, List<TMatch> matches) {
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : matches) {
            matchZone.put(m.getId(), m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone());
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchZone.keySet())
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        Map<String, Integer> result = new HashMap<>();
        for (TMatchParticipant p : parts) {
            result.merge(matchZone.getOrDefault(p.getMatchId(), "CENTER"), 1, Integer::sum);
        }
        return result;
    }

    /**
     * 结算单场排名赛:跨本场全部轮次(每个选手一个轮次)聚合多裁判×多维度分,
     * 用 RANKING 策略算总分排名,按本圈剩余名额晋级;晋级线同分并列时保持 PENDING。
     */
    private void settleRankMatch(TMatch match, RuleConfigHolder rc, int advanceQuota,
                                 int zoneBase, Map<String, Integer> zoneAdvanced) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId())
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            markMatchSettled(match);
            return;
        }
        List<Long> partIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();
        Map<Long, TCompetitor> compMap = partIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(partIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        // 退赛选手不参与排名、不占用晋级名额(保持 WITHDRAWN)
        List<Long> competitorIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull)
            .filter(cid -> {
                TCompetitor c = compMap.get(cid);
                return c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
            })
            .toList();
        if (competitorIds.isEmpty()) {
            markMatchSettled(match);
            return;
        }
        // 未打分守卫:从未被任何裁判打分的选手不允许随结算"0 分自动晋级"
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Map<Long, Long> scoreCountByCompetitor = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds)
                    .select(TRoundScore::getCompetitorId))
                .stream()
                .filter(rs -> rs.getCompetitorId() != null)
                .collect(Collectors.groupingBy(TRoundScore::getCompetitorId, Collectors.counting()));
        List<String> unjudged = competitorIds.stream()
            .map(compMap::get).filter(Objects::nonNull)
            .filter(c -> !scoreCountByCompetitor.containsKey(c.getId()))
            .map(TCompetitor::getName)
            .toList();
        if (!unjudged.isEmpty()) {
            throw new ServiceException("圈内仍有 {} 名选手未打分(未标记退赛): {},请先完成打分或标记退赛后再结算",
                unjudged.size(), unjudged);
        }

        // 分数分布在各自轮次,跨本场全部轮次汇总
        List<TRoundScore> allScores = roundIds.isEmpty() ? List.of() : roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.RANKING)
            .scoringConfig(rc != null ? rc.getScoring() : null)
            .competitorIds(competitorIds)
            .rawScores(allScores)
            .build();
        List<MatchScoreResult> results = scoringEngine.compute(input);

        // 回写 participant 总分/排名(胜负状态统一在下面按晋级结果标)
        for (MatchScoreResult r : results) {
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setScoreValue(r.getScoreValue());
            pUpd.setRankInMatch(r.getRankInMatch() == null ? null : r.getRankInMatch().longValue());
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, r.getCompetitorId()));
        }

        String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);

        Map<Long, java.math.BigDecimal> scores = new HashMap<>();
        for (MatchScoreResult r : results) {
            if (r.getCompetitorId() != null) {
                scores.put(r.getCompetitorId(), r.getScoreValue() != null ? r.getScoreValue() : java.math.BigDecimal.ZERO);
            }
        }
        List<Long> sortedCids = new ArrayList<>(scores.keySet());
        sortedCids.sort((a, b) -> scores.getOrDefault(b, java.math.BigDecimal.ZERO)
            .compareTo(scores.getOrDefault(a, java.math.BigDecimal.ZERO)));
        Map<Long, Integer> ranks = com.dance.street.game.engine.scoring.RankCalculator.rank(scores);

        // 剩余名额已满:本场(重复结算/异常数据)所有人淘汰
        if (remaining <= 0) {
            for (int i = 0; i < sortedCids.size(); i++) {
                markRankResult(sortedCids.get(i), OutcomeStatusEnum.ELIMINATED.getCode(),
                    (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
            }
            markMatchSettled(match);
            log.info("排名赛场次[{}]结算:晋级名额已满,{}名选手淘汰", match.getId(), sortedCids.size());
            return;
        }

        // 检测晋级线上的同分并列:晋级名额被同分横跨时,同分者保持 PENDING 由导播台定夺
        if (remaining < sortedCids.size()) {
            java.math.BigDecimal cutoffScore = scores.get(sortedCids.get(remaining - 1));
            List<Long> tiedAtCutoff = new ArrayList<>();
            for (Long cid : sortedCids) {
                if (scores.get(cid).compareTo(cutoffScore) == 0) {
                    tiedAtCutoff.add(cid);
                }
            }
            int lastTiedPos = sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1));
            if (tiedAtCutoff.size() > 1 && remaining <= lastTiedPos) {
                int firstTiedPos = sortedCids.indexOf(tiedAtCutoff.get(0));
                // 明确晋级者:同分群之前
                for (int i = 0; i < firstTiedPos; i++) {
                    Long cid = sortedCids.get(i);
                    markRankResult(cid, OutcomeStatusEnum.ADVANCE.getCode(),
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
                }
                // 明确淘汰者:同分群之后
                for (int i = lastTiedPos + 1; i < sortedCids.size(); i++) {
                    Long cid = sortedCids.get(i);
                    markRankResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
                }
                zoneAdvanced.put(zone, alreadyAdvanced + firstTiedPos);
                // 同分者保持 PENDING,由导播台在中间态调整中手动决定晋级
                log.info("排名赛场次[{}]晋级线出现{}名同分并列,保持待定等待导播台调整", match.getId(), tiedAtCutoff.size());
                markMatchSettled(match);
                return;
            }
        }

        // 无同分问题:正常结算
        for (int i = 0; i < sortedCids.size(); i++) {
            Long cid = sortedCids.get(i);
            boolean advance = i < remaining;
            markRankResult(cid, advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
        }
        zoneAdvanced.put(zone, alreadyAdvanced + Math.min(remaining, sortedCids.size()));
        markMatchSettled(match);
        log.info("排名赛场次[{}]已结算,共{}名选手,晋级{}名", match.getId(), sortedCids.size(),
            Math.min(remaining, sortedCids.size()));
    }

    /** 回写排名赛参赛者结果:competitor 赛段级状态 + participant 场次级状态 */
    private void markRankResult(Long cid, String outcome, Long finalRank,
                                Map<Long, Integer> ranks, TMatch match) {
        TCompetitor cupd = new TCompetitor();
        cupd.setId(cid);
        cupd.setFinalRank(finalRank);
        cupd.setOutcomeStatus(outcome);
        competitorMapper.updateById(cupd);

        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setRankInMatch(ranks.get(cid) != null ? ranks.get(cid).longValue() : null);
        pUpd.setOutcomeStatus(outcome);
        participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, cid));
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
        TCompetitor cupd = new TCompetitor();
        cupd.setId(competitorId);
        cupd.setFinalRank(finalRank);
        cupd.setOutcomeStatus(outcome);
        competitorMapper.updateById(cupd);

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
            cr.setZone(m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone());
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
