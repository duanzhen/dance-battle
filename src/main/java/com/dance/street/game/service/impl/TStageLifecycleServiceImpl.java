package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.AddGuestBo;
import com.dance.street.game.domain.bo.CalculateAdvancementBo;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.CircleAssignVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.TransitionConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
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
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITCompetitorMemberService;
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final ScoringEngine scoringEngine = new ScoringEngine();
    private final TRoundScoreMapper roundScoreMapper;
    private final ITCompetitorService competitorService;
    private final ITCompetitorMemberService competitorMemberService;
    private final ITStageService stageService;
    private final StageGeneratorFactory generatorFactory = new StageGeneratorFactory();
    private final ITScoredMatchService scoredMatchService;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ITRefereeStageService refereeStageService;

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

        // 按 seedRank 升序排种子(null 视为最大),写回 1..n
        comps.sort(Comparator.comparing(c -> c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank()));
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            c.setSeedRank((long) (i + 1));
            competitorMapper.updateById(c);
        }

        stage.setIsInitialized(1L);
        stage.setStatus(StageConstants.STAGE_PENDING);
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
        if (!perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段尚未初始化,请先 initialize");
        }
        // 海选赛/排名赛允许跳过显式初始化(兜底:自动初始化)
        if (perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            InitializeStageBo initBo = new InitializeStageBo();
            initBo.setStageId(stage.getId());
            initialize(initBo);
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
            int effectiveCircles = Math.min(cfgCircles, Math.max(1, comps.size()));
            List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
            boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
            if (explicitQuota) {
                // 每圈独立晋级名额:只校验非负,不要求均分
                for (Integer q : perCircleCfg) {
                    if (q == null || q < 0) {
                        throw new ServiceException("每圈晋级人数配置非法(不能为负): {}", perCircleCfg);
                    }
                }
            } else if (advanceCount > 0 && advanceCount % effectiveCircles != 0) {
                throw new ServiceException("海选总晋级数[{}]无法按{}圈均分,请调整晋级名额或圈数(当前参赛{}人)",
                    advanceCount, effectiveCircles, comps.size());
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
                String json = RuleConfigParser.toJsonPromotionRule(Map.of("1", target));
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
    public List<CircleAssignVo> randomCircles(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅海选赛段支持随机抽取圈");
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int circles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
        if (circles <= 1) {
            throw new ServiceException("该海选未分圈(circles=1),无需随机抽取");
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())
            && !StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            throw new ServiceException("赛段已开始或结束,无法重新抽取");
        }

        // 已生成过对阵:仅当所有场次仍未开始时允许重抽(清空后重新随机分场)
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        if (exist > 0) {
            long started = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .ne(TMatch::getStatus, StageConstants.MATCH_PENDING));
            if (started > 0) {
                throw new ServiceException("已有场次开始,无法重新抽取");
            }
            clearStageMatches(stageId);
            log.info("海选赛段[{}]随机抽取前已清空旧对阵", stageId);
        }

        // 随机分圈生成(内部自动初始化)
        GenerateMatchesBo gm = new GenerateMatchesBo();
        gm.setStageId(stageId);
        generateMatchesInternal(gm, true);

        // 组装按圈返回
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        List<CircleAssignVo> result = new ArrayList<>();
        for (TMatch m : matches) {
            CircleAssignVo vo = new CircleAssignVo();
            vo.setMatchId(m.getId());
            vo.setMatchName(m.getName());
            vo.setDisplayZone(m.getDisplayZone());
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .isNotNull(TMatchParticipant::getCompetitorId)
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            List<CircleAssignVo.CompetitorInfo> comps = new ArrayList<>();
            for (TMatchParticipant p : parts) {
                TCompetitor c = competitorMapper.selectById(p.getCompetitorId());
                CircleAssignVo.CompetitorInfo ci = new CircleAssignVo.CompetitorInfo();
                ci.setCompetitorId(p.getCompetitorId());
                ci.setName(c != null ? c.getName() : null);
                ci.setNumber(c != null ? c.getNumber() : null);
                ci.setSlotIndex(p.getDisplaySlotIndex());
                comps.add(ci);
            }
            vo.setCompetitors(comps);
            result.add(vo);
        }
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return result;
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
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            List<TMatchParticipant> real = parts.stream()
                .filter(p -> p.getCompetitorId() != null)
                .toList();
            if (real.size() >= 2) {
                continue; // 正常对决,不处理
            }
            // 0 参赛者的场次:仅首轮视为双边轮空可结算;后续轮次是等待上游胜者填入的占位,
            // 不能按轮空结算,否则整条淘汰链会在开赛瞬间塌掉
            if (real.isEmpty()
                && m.getDisplayCol() != null && m.getDisplayCol() > 1L) {
                continue;
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
            settled++;
        }
        if (settled > 0) {
            log.info("赛段[{}]轮空场次自动结算 {} 场", stageId, settled);
        }
        return settled;
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
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        List<Long> queue = comps.stream().map(TCompetitor::getId).collect(Collectors.toCollection(ArrayList::new));

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
        return queue;
    }

    /** 擂台赛积分:统计本赛段全部场次中参赛者的胜场数(每胜一场 +1) */
    private Map<Long, Integer> arenaPoints(Long stageId) {
        Map<Long, Integer> wins = new HashMap<>();
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (matches.isEmpty()) {
            return wins;
        }
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.WIN.getCode()));
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                wins.merge(p.getCompetitorId(), 1, Integer::sum);
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
        // 流程规范:上一赛段必须已结束(SETTLED),否则不允许开始本赛段
        TStage prev = resolvePrevStage(stage);
        if (stage.getPrevStageId() != null && prev == null) {
            throw new ServiceException("上一赛段不存在,无法开始本赛段");
        }
        if (prev != null) {
            if (!StageConstants.STAGE_SETTLED.equals(prev.getStatus())) {
                throw new ServiceException("上一赛段[{}]尚未结束,无法开始本赛段", prev.getName());
            }
            // 所有流转必须经过中间态:上一赛段已结束但本赛段尚未接收晋级者时,禁止直接开赛
            long confirmed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .isNotNull(TCompetitor::getSourceCompetitorId));
            if (confirmed == 0) {
                // 源赛段确实没有可确认对象(无晋级者且无同分待定)时,允许直接开始,
                // 避免「全员淘汰 / 无晋级名额 / 目标赛段仅 GUEST 直入」等场景死锁
                long srcAdvance = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, prev.getId())
                    .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
                long srcPending = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, prev.getId())
                    .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
                if (srcAdvance > 0 || srcPending > 0) {
                    throw new ServiceException(
                        "上一赛段[{}]已结束,请先在中间态「确认晋级」后再开始本赛段", prev.getName());
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
        // 轮空场次自动晋级:人数不足 2 的幂时,单边轮空直接判胜填下游/标晋级,无需人工判罚
        if (StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            settleByeMatches(stageId);
        }
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
        appendStageCompetitor(stageId, competitorId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = mustGetStage(stageId);
        // 海选/排名赛均为逐选手轮次:签到/补签到选手直接挂入未结算圈场次,可被裁判打分。
        // 已生成对阵但尚未开赛(PENDING)时同样挂入,否则补签到选手会从打分中"消失"
        // (不参与任何场次,结算后无晋级/淘汰结果,且无任何提示)。
        boolean perCompetitor = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        boolean attachable = StageConstants.STAGE_GAMING.equals(stage.getStatus())
            || StageConstants.STAGE_PENDING.equals(stage.getStatus());
        if (!perCompetitor || !attachable) {
            return;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
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
            // 自动择优:分圈配置了每圈名额时,选"剩余名额余量"最足的圈;
            // 未配置时等价于选人数最少的圈
            target = pickCircleForCheckin(candidates, stage);
        }
        if (target == null) {
            return;
        }
        appendParticipantWithRound(target, competitorId);
        log.info("海选/排名赛段[{}]补签到:参赛方[{}]挂入场次[{}]", stageId, competitorId, target.getId());
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
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
    public TCompetitorVo addGuest(AddGuestBo bo) {
        TStage stage = mustGetStage(bo.getStageId());
        // GUEST 禁止加入海选(海选走签到/补签到流程)
        if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("海选赛段不支持添加 GUEST");
        }
        // 仅赛段规划/未开始态(DRAFT/PENDING)且未初始化可加入:
        // 名单锁定(initialize)后对阵随之生成,中途加入的 GUEST 将无法按抽签结果排位
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())
            && !StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            throw new ServiceException("仅赛段规划/未开始状态(DRAFT/PENDING)可添加 GUEST,当前状态: {}", stage.getStatus());
        }
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法再添加 GUEST");
        }
        if (bo.getName() == null || bo.getName().isBlank()) {
            throw new ServiceException("GUEST 名称不能为空");
        }
        // 中间态调整:GUEST 可挤掉名次靠后的已确认晋级者,总人数不超计划(轮空占位也算);
        // GUEST 自身最多占满计划名额
        long plan = stage.getTeamCountStart() != null && stage.getTeamCountStart() > 0 ? stage.getTeamCountStart() : 0L;
        long guestCount = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .eq(TCompetitor::getRemark, "GUEST"));
        long confirmedAdvancers = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .isNotNull(TCompetitor::getSourceCompetitorId));
        if (plan > 0 && guestCount + 1 > plan) {
            throw new ServiceException("GUEST 已占满赛段计划 {} 人,无法继续添加 GUEST", plan);
        }
        // 添加后总人数(已确认晋级者 + GUEST)超过计划时,挤掉名次靠后(种子号大)的已确认晋级者:
        // 从下一赛段移除,并在来源赛段标记淘汰(晋级名单不再显示)
        int needPush = (int) Math.max(0, guestCount + confirmedAdvancers + 1 - plan);
        if (needPush > 0) {
            List<TCompetitor> bottom = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .isNotNull(TCompetitor::getSourceCompetitorId)
                .orderByDesc(TCompetitor::getSeedRank)
                .last("limit " + needPush));
            for (TCompetitor c : bottom) {
                if (c.getSourceCompetitorId() != null) {
                    competitorMapper.update(null, Wrappers.<TCompetitor>lambdaUpdate()
                        .set(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ELIMINATED.getCode())
                        .eq(TCompetitor::getId, c.getSourceCompetitorId()));
                }
            }
            if (!bottom.isEmpty()) {
                competitorService.deleteWithValidByIds(
                    bottom.stream().map(TCompetitor::getId).toList(), true);
            }
            log.info("赛段[{}]添加 GUEST 挤掉 {} 名已确认晋级者(名次靠后)", stage.getId(), bottom.size());
        }

        // 1. 创建参赛单位:标记 GUEST;按落位模式分配种子(顶前/队尾/指定种子位)
        String placement = StringUtils.upperCase(StringUtils.trimToEmpty(bo.getPlacement()));
        Long specifiedSeed = "SPECIFIED".equals(placement) ? bo.getSeedRank() : null;
        TCompetitorBo cbo = new TCompetitorBo();
        cbo.setTournamentId(stage.getTournamentId());
        cbo.setStageId(stage.getId());
        cbo.setType(bo.getType() == null ? 0L : bo.getType());
        cbo.setName(bo.getName().trim());
        cbo.setNumber(StringUtils.isNotBlank(bo.getNumber()) ? bo.getNumber().trim() : nextGuestNumber(stage));
        cbo.setSeedRank(seedRankForGuest(stage, placement, specifiedSeed));
        cbo.setRemark("GUEST");
        TCompetitorVo vo = competitorService.insertByBo(cbo);
        Long competitorId = vo.getId();

        // 2. 可选:关联选手(校验选手属于当前赛事)
        if (bo.getPlayerId() != null) {
            TPlayer player = playerMapper.selectById(bo.getPlayerId());
            if (player == null || !Objects.equals(stage.getTournamentId(), player.getTournamentId())) {
                throw new ServiceException("选手不存在或不属于当前赛事");
            }
            TCompetitorMemberBo mbo = new TCompetitorMemberBo();
            mbo.setTournamentId(stage.getTournamentId());
            mbo.setCompetitorId(competitorId);
            mbo.setPlayerId(player.getId());
            mbo.setRole("MEMBER");
            competitorMemberService.insertByBo(mbo);
        }

        // 3. 不自动挂入任何场次:GUEST 先进入参赛方池,由导播按外部抽签结果设定种子顺序,
        //    之后 initialize → generateMatches 会连同既有晋级者一起生成对阵
        //    (GUEST 胜出即按赛段晋级名额正常占位)

        log.info("GUEST[{}](id={})加入赛段[{}]({})", bo.getName().trim(), competitorId, stage.getId(), stage.getStageMode());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return vo;
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

    /**
     * 分圈海选晋级排序:把晋级者按"圈内名次轮转"交叉排列
     * (圈1第1、圈2第1、圈3第1…、圈1第2、圈2第2…),而非按全局排名整圈集中。
     * 仅当源赛段为 AUDITION 且实际生成多个圈(场次)时生效;无圈信息时保持全局排名顺序。
     */
    private void reorderAdvancersByCircleRank(TStage stage, List<TCompetitor> advancers) {
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode()) || advancers == null || advancers.size() < 2) {
            return;
        }
        List<TMatch> srcMatches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (srcMatches.size() < 2) {
            return; // 未分圈,保持原顺序
        }
        // 圈序号/名额/排名起点(与 settleAuditionStage 口径一致)
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        int advanceCount = readStageAdvanceCount(stage);
        int circles = (int) srcMatches.stream().map(this::zoneOfMatch).distinct().count();
        circles = Math.max(1, circles);
        int perCircle = circles > 1 ? advanceCount / circles : advanceCount;
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        List<String> zoneOrder = new ArrayList<>();
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : srcMatches) {
            String zone = zoneOfMatch(m);
            if (!zoneOrdinal.containsKey(zone)) {
                int quota = explicitQuota && ordinal < perCircleCfg.size()
                    ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
                zoneOrdinal.put(zone, ordinal);
                zoneBase.put(zone, acc);
                zoneOrder.add(zone);
                acc += quota;
                ordinal++;
            }
        }
        // 晋级者 -> 所在圈(取自其源赛段场次的 displayZone)
        List<Long> srcMatchIds = srcMatches.stream().map(TMatch::getId).toList();
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : srcMatches) {
            matchZone.put(m.getId(), zoneOfMatch(m));
        }
        Map<Long, String> zoneByCompetitor = new HashMap<>();
        if (!srcMatchIds.isEmpty()) {
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, srcMatchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId)
                    .select(TMatchParticipant::getCompetitorId, TMatchParticipant::getMatchId))
                .forEach(p -> zoneByCompetitor.putIfAbsent(p.getCompetitorId(),
                    matchZone.getOrDefault(p.getMatchId(), "CENTER")));
        }
        advancers.sort((a, b) -> {
            String za = zoneByCompetitor.get(a.getId());
            String zb = zoneByCompetitor.get(b.getId());
            long fa = a.getFinalRank() == null ? Long.MAX_VALUE : a.getFinalRank();
            long fb = b.getFinalRank() == null ? Long.MAX_VALUE : b.getFinalRank();
            if (za == null || zb == null || !zoneOrdinal.containsKey(za) || !zoneOrdinal.containsKey(zb)) {
                // 无圈信息(异常数据):按全局排名兜底
                int cmp = Long.compare(fa, fb);
                return cmp != 0 ? cmp : Long.compare(a.getId(), b.getId());
            }
            long ra = fa - zoneBase.getOrDefault(za, 0); // 圈内名次(1 基)
            long rb = fb - zoneBase.getOrDefault(zb, 0);
            if (ra != rb) {
                return Long.compare(ra, rb); // 圈内名次优先:各圈第1 → 各圈第2 → …
            }
            int oa = zoneOrdinal.get(za);
            int ob = zoneOrdinal.get(zb);
            if (oa != ob) {
                return Integer.compare(oa, ob); // 同圈内名次按圈序
            }
            return Long.compare(a.getId(), b.getId());
        });
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

    /** 追加参赛方并新建轮次(海选补签到:每个参赛方一个独立轮次,裁判逐选手打分) */
    private void appendParticipantWithRound(TMatch target, Long competitorId) {
        long nextSlot = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, target.getId())
                .select(TMatchParticipant::getDisplaySlotIndex))
            .stream().mapToLong(p -> p.getDisplaySlotIndex() == null ? 0L : p.getDisplaySlotIndex())
            .max().orElse(0L) + 1L;
        long nextRound = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, target.getId())
                .select(TMatchRound::getRoundSequence))
            .stream().mapToLong(r -> r.getRoundSequence() == null ? 0L : r.getRoundSequence())
            .max().orElse(0L) + 1L;

        TMatchParticipant p = new TMatchParticipant();
        p.setTenantId(target.getTenantId());
        p.setTournamentId(target.getTournamentId());
        p.setMatchId(target.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(nextSlot);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);

        // 每个参赛方一个独立轮次,裁判逐选手打分
        TMatchRound round = new TMatchRound();
        round.setTenantId(target.getTenantId());
        round.setTournamentId(target.getTournamentId());
        round.setMatchId(target.getId());
        round.setRoundSequence(nextRound);
        round.setCompetitorId(competitorId);
        round.setStatus(target.getStatus());
        matchRoundMapper.insert(round);

        log.info("参赛方[{}]挂入场次[{}](slot={},round={})", competitorId, target.getId(), nextSlot, nextRound);
    }

    /** 场次内下一可用展示位 */
    private long nextSlotIndex(TMatch target) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, target.getId())
                .select(TMatchParticipant::getDisplaySlotIndex))
            .stream().mapToLong(p -> p.getDisplaySlotIndex() == null ? 0L : p.getDisplaySlotIndex())
            .max().orElse(0L) + 1L;
    }

    /** GUEST 选手号:留空时按本赛段最大数字型选手号 +1 生成,前缀 G 与常规选手区分 */
    private String nextGuestNumber(TStage stage) {
        List<String> numbers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getNumber))
            .stream().map(TCompetitor::getNumber)
            .filter(java.util.Objects::nonNull)
            .toList();
        // 同时统计纯数字号与已有 G 前缀号,避免重复(如第二个 GUEST 仍是 G1)
        long maxNum = 0L;
        for (String n : numbers) {
            if (n.matches("\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n));
            } else if (n.matches("G\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n.substring(1)));
            }
        }
        return "G" + (maxNum + 1);
    }

    /** 下一可用种子顺位(GUEST 排到队尾) */
    private long nextSeedRank(TStage stage) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getSeedRank))
            .stream().mapToLong(c -> c.getSeedRank() == null ? 0L : c.getSeedRank())
            .max().orElse(0L) + 1L;
    }

    /**
     * GUEST 种子位,按落位模式:
     * <ul>
     *   <li>FRONT:顶前——按加入顺序占据 1..G,原有参赛者种子顺延(保持相对顺序)</li>
     *   <li>TAIL:队尾——排在当前最后</li>
     *   <li>SPECIFIED:指定种子位——落在指定位置,已有种子 >= 该位置者顺延</li>
     *   <li>AUTO:淘汰赛 SEED(首尾交叉)顶前,其余队尾</li>
     * </ul>
     */
    private long seedRankForGuest(TStage stage, String placement, Long specified) {
        if ("SPECIFIED".equals(placement)) {
            if (specified == null || specified < 1L) {
                throw new ServiceException("指定种子位需为正整数");
            }
            long plan = stage.getTeamCountStart() != null && stage.getTeamCountStart() > 0
                ? stage.getTeamCountStart() : Long.MAX_VALUE;
            if (specified > plan) {
                throw new ServiceException("指定种子位[{}]超出赛段计划规模[{}]", specified, plan);
            }
            List<TCompetitor> all = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getId, TCompetitor::getSeedRank));
            for (TCompetitor c : all) {
                if (c.getSeedRank() != null && c.getSeedRank() >= specified) {
                    TCompetitor upd = new TCompetitor();
                    upd.setId(c.getId());
                    upd.setSeedRank(c.getSeedRank() + 1);
                    competitorMapper.updateById(upd);
                }
            }
            return specified;
        }
        boolean front = "FRONT".equals(placement);
        if ("AUTO".equals(placement) || StringUtils.isBlank(placement)) {
            front = StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode()) && isSeedPairing(stage);
        }
        if (front) {
            List<TCompetitor> all = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getId, TCompetitor::getSeedRank, TCompetitor::getRemark));
            long guestCount = 0L;
            for (TCompetitor c : all) {
                if ("GUEST".equals(c.getRemark())) {
                    guestCount++;
                }
            }
            // 原有参赛者(非 GUEST)种子顺延 +1,保持相对顺序;GUEST 自身保持 1..G 不参与顺延
            for (TCompetitor c : all) {
                if (!"GUEST".equals(c.getRemark()) && c.getSeedRank() != null) {
                    TCompetitor upd = new TCompetitor();
                    upd.setId(c.getId());
                    upd.setSeedRank(c.getSeedRank() + 1);
                    competitorMapper.updateById(upd);
                }
            }
            return guestCount + 1L;
        }
        return nextSeedRank(stage);
    }

    /** 是否首尾交叉(SEED)配对:优先取 ruleConfig.knockout.pairingMode;未配置时按与 generateMatches 相同的推断 */
    private boolean isSeedPairing(TStage stage) {
        String pairingMode = null;
        try {
            RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
            if (rc != null && rc.getKnockout() != null) {
                pairingMode = rc.getKnockout().getPairingMode();
            }
        } catch (Exception ignored) {
            // 配置解析失败按未配置处理
        }
        if (StringUtils.isNotBlank(pairingMode)) {
            return "SEED".equalsIgnoreCase(pairingMode);
        }
        TStage prev = stage.getPrevStageId() != null ? stageMapper.selectById(stage.getPrevStageId()) : null;
        return prev != null
            && (StageModeEnum.AUDITION.getCode().equals(prev.getStageMode())
                || StageModeEnum.RANK.getCode().equals(prev.getStageMode()));
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
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        // 不再自动晋级:裁判判完仅出预排/晋级者,由导播台在中间态「确认晋级」时正式写入下一赛段
        return StageConstants.STAGE_SETTLED;
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
    public int calculateAdvancement(CalculateAdvancementBo bo) {
        TStage stage = mustGetStage(bo.getStageId());
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅 SETTLED 状态的赛段可计算晋级");
        }
        Long nextStageId = resolveNextStageId(stage);
        if (nextStageId == null) {
            return 0;
        }
        TStage next = stageMapper.selectById(nextStageId);
        if (next == null) {
            return 0;
        }
        // 幂等:下一赛段若已存在「带来源」的参赛方,视为已晋级,直接返回 0
        long existed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, nextStageId)
            .isNotNull(TCompetitor::getSourceCompetitorId));
        if (existed > 0) {
            return 0;
        }
        // 中间态编排必须先于生成对阵:下一赛段已生成对阵时,晋级者无法挂入,拒绝确认
        long nextMatchCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, nextStageId));
        if (nextMatchCount > 0) {
            throw new ServiceException("下一赛段[{}]已生成对阵,请先清除对阵后再确认晋级", next.getName());
        }
        // 排名赛同分待定必须先在中间态裁决:源赛段仍残留 PENDING(待定)参赛方时拒绝确认,避免被静默跳过
        long pendingInSource = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
        if (pendingInSource > 0) {
            throw new ServiceException(
                "源赛段[{}]仍有 {} 名同分待定参赛方未裁决,请先在中间态处理后再确认晋级",
                stage.getName(), pendingInSource);
        }
        List<TCompetitor> advancers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
            .orderByAsc(TCompetitor::getFinalRank));
        if (advancers.isEmpty()) {
            return 0;
        }
        // finalRank 空值排最后,保证截取前 N 名时顺序与预排一致
        advancers.sort(Comparator
            .comparing((TCompetitor c) -> c.getFinalRank() == null ? Long.MAX_VALUE : c.getFinalRank())
            .thenComparing(TCompetitor::getId));
        // 中间态调整结果必须符合下一赛段计划规模:现有参赛方(GUEST) + 本次晋级者不得超过 teamCountStart(轮空占位也算)。
        // 超出剩余名额时按 finalRank 顺序只接收前 N 名(GUEST 已顶替前几名种子,其余晋级者顺延)
        long nextPlan = next.getTeamCountStart() != null && next.getTeamCountStart() > 0
            ? next.getTeamCountStart() : 0L;
        long existingInNext = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, nextStageId));
        if (nextPlan > 0 && advancers.size() > nextPlan - existingInNext) {
            int accepted = (int) Math.max(0L, nextPlan - existingInNext);
            List<TCompetitor> dropped = accepted >= advancers.size()
                ? List.of() : new ArrayList<>(advancers.subList(accepted, advancers.size()));
            // 被名额挤掉的晋级者标记淘汰(保留 finalRank 排名),避免「源赛段 ADVANCE
            // 但未进入下一赛段」的状态矛盾,也保证 prebracket/确认晋级与最终名单一致
            for (TCompetitor c : dropped) {
                TCompetitor upd = new TCompetitor();
                upd.setId(c.getId());
                upd.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
                competitorMapper.updateById(upd);
            }
            if (!dropped.isEmpty()) {
                log.warn("赛段[{}]晋级者 {} 人超出下一赛段[{}]剩余名额 {} 人,按 finalRank 取前 {} 名;"
                        + "被挤出者 {} 人已标记淘汰: {}",
                    stage.getId(), advancers.size(), next.getName(), nextPlan - existingInNext, accepted,
                    dropped.size(), dropped.stream().map(TCompetitor::getName).toList());
            }
            if (accepted == 0) {
                return 0;
            }
            advancers = new ArrayList<>(advancers.subList(0, accepted));
        }
        // 分圈海选晋级:按"圈内名次轮转"交叉排序(圈1第1、圈2第1、…、圈1第2、圈2第2、…),
        // 使头尾交叉的淘汰赛种子均匀分布各圈强者,而不是整圈集中在前段
        reorderAdvancersByCircleRank(stage, advancers);

        Map<Long, Long> overrides = bo.getSeedOverrides();

        List<TCompetitor> ordered = new ArrayList<>(advancers);
        // 已有参赛方(通常为提前加入的 GUEST)占用的种子位;晋级者按 finalRank 顺序填充剩余空位,
        // 保持相对顺序且不与 GUEST 冲突(预排 seedOverrides 为绝对位置,优先生效)
        Set<Long> occupiedSeeds = new HashSet<>();
        competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, nextStageId)
                .select(TCompetitor::getSeedRank))
            .forEach(c -> {
                if (c.getSeedRank() != null) {
                    occupiedSeeds.add(c.getSeedRank());
                }
            });
        long nextFreeSeed = 1L;
        for (int i = 0; i < ordered.size(); i++) {
            TCompetitor src = ordered.get(i);
            Long override = overrides == null ? null : overrides.get(src.getId());
            long seed;
            if (override != null) {
                if (occupiedSeeds.contains(override)) {
                    throw new ServiceException(
                        "种子覆盖位[{}]已被占用(已有参赛方或其他晋级者),请先在中间态调整预排位置", override);
                }
                seed = override;
            } else {
                while (occupiedSeeds.contains(nextFreeSeed)) {
                    nextFreeSeed++;
                }
                seed = nextFreeSeed++;
            }
            occupiedSeeds.add(seed);
            TCompetitor nc = new TCompetitor();
            // 显式带租户,避免无登录租户上下文(如裁判端/直连调用)时 tenant_id 插入报错
            nc.setTenantId(stage.getTenantId());
            nc.setTournamentId(stage.getTournamentId());
            nc.setStageId(next.getId());
            nc.setSourceCompetitorId(src.getId());
            nc.setType(src.getType());
            nc.setName(src.getName());
            nc.setNumber(src.getNumber());
            // 保留 GUEST 标记等备注,链式赛段中 GUEST 晋级后仍保持身份标识
            nc.setRemark(src.getRemark());
            nc.setSeedRank(seed);
            nc.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(nc);
            // 复制参赛成员关联(选手信息/照片),否则下一赛段参赛方的 playerList 为空、头像取不到
            List<TCompetitorMember> srcMembers = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .eq(TCompetitorMember::getCompetitorId, src.getId()));
            for (TCompetitorMember sm : srcMembers) {
                TCompetitorMember nm = new TCompetitorMember();
                nm.setTenantId(nc.getTenantId());
                nm.setTournamentId(nc.getTournamentId());
                nm.setCompetitorId(nc.getId());
                nm.setPlayerId(sm.getPlayerId());
                nm.setRole(sm.getRole());
                nm.setRemark(sm.getRemark());
                competitorMemberMapper.insert(nm);
            }
        }
        log.info("赛段[{}]晋级 {} 人到下一赛段[{}]", stage.getId(), ordered.size(), nextStageId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return ordered.size();
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
            return stageMapper.selectById(stage.getPrevStageId());
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
        int circles = (int) matches.stream()
            .map(m -> m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone())
            .distinct().count();
        circles = Math.max(1, circles);
        int perCircle = circles > 1 ? advanceCount / circles : advanceCount;
        // 未显式配置每圈名额时才要求均分可整除
        if (!explicitQuota && advanceCount > 0 && advanceCount % circles != 0) {
            throw new ServiceException("海选总晋级数[{}]无法按实际[{}]圈均分,请调整晋级名额或圈数", advanceCount, circles);
        }

        // 圈序号(displayRow 顺序) + 每圈晋级名额 + 全局排名起点(前序各圈名额累加)
        List<String> orderedZones = new ArrayList<>();
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneQuota = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : matches) {
            String zone = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            if (zoneOrdinal.putIfAbsent(zone, ordinal) == null) {
                int quota = explicitQuota && ordinal < perCircleCfg.size()
                    ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
                zoneQuota.put(zone, quota);
                zoneBase.put(zone, acc);
                acc += quota;
                orderedZones.add(zone);
                ordinal++;
            }
        }
        // 圈内已晋级数(含已结算正式圈与加赛,支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countAuditionAdvancedByZone(stage, matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
            settleAuditionMatch(match, zoneQuota.getOrDefault(zone, perCircle),
                zoneBase.getOrDefault(zone, 0), zoneAdvanced);
        }
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
        Map<Long, Integer> ranks = com.dance.street.game.engine.scoring.RankCalculator.rank(scores);

        List<Long> sortedCids = new ArrayList<>(scores.keySet());
        sortedCids.sort((a, b) -> {
            java.math.BigDecimal sa = scores.getOrDefault(a, java.math.BigDecimal.ZERO);
            java.math.BigDecimal sb = scores.getOrDefault(b, java.math.BigDecimal.ZERO);
            return sb.compareTo(sa);
        });

        // 0 分选手(弃权/缺席,含未打分)不参与晋级,也不参与同分加赛;
        // 正分人数不足晋级名额时,剩余名额空缺(下一赛段对应位置轮空)
        int positiveCount = 0;
        for (Long cid : sortedCids) {
            if (scores.get(cid).compareTo(java.math.BigDecimal.ZERO) > 0) {
                positiveCount++;
            }
        }

        // 剩余名额已满,本场(加赛)所有人淘汰
        if (remaining <= 0) {
            for (int i = 0; i < sortedCids.size(); i++) {
                markAuditionResult(sortedCids.get(i), OutcomeStatusEnum.ELIMINATED.getCode(),
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
            markAuditionResult(cid, advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
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
    }

    /**
     * 创建加赛场次:只有同分选手参与,胜负决出后由 completeStage 再次结算。
     */
    private void createTiebreakerMatch(TMatch parentMatch, List<Long> tiedCompetitorIds) {
        // 连续同分加赛上限:多次加赛仍未决出时拒绝继续生成,提示人工裁决/重置,
        // 避免操作失误导致加赛场次无限堆积、赛段永远无法完成
        long tiebreakerCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, parentMatch.getStageId())
            .likeRight(TMatch::getRemark, "同分加赛"));
        if (tiebreakerCount >= 5) {
            throw new ServiceException(
                "海选已连续 {} 轮同分加赛仍未决出晋级者,请人工裁决(如调整打分或重置赛段后重排)",
                tiebreakerCount);
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
        tb.setRemark("同分加赛," + tiedCompetitorIds.size() + "人争晋级名额");
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
        int perCircle = circles > 1 ? advanceCount / circles : advanceCount;
        if (!explicitQuota && advanceCount > 0 && advanceCount % circles != 0) {
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
                int quota = explicitQuota && ordinal < perCircleCfg.size()
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
    @Transactional(rollbackFor = Exception.class)
    public int promoteReplacement(Long stageId, Long withdrawnCompetitorId, Long replacementCompetitorId) {
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅海选赛段支持弃权/顶替");
        }
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅已结算(SETTLED)的海选赛段可执行弃权/顶替");
        }
        if (withdrawnCompetitorId == null && replacementCompetitorId == null) {
            throw new ServiceException("请指定弃权者或顶替者");
        }
        if (Objects.equals(withdrawnCompetitorId, replacementCompetitorId)) {
            throw new ServiceException("弃权者与顶替者不能是同一参赛方");
        }
        // 幂等:下一赛段若已接收晋级者,不允许再调整
        Long nextStageId = resolveNextStageId(stage);
        if (nextStageId != null) {
            long existed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, nextStageId)
                .isNotNull(TCompetitor::getSourceCompetitorId));
            if (existed > 0) {
                throw new ServiceException("下一赛段已接收晋级者,无法再调整弃权/顶替");
            }
        }
        // 1) 弃权/移除:晋级者 ADVANCE -> WITHDRAWN,并把其后的晋级者名次整体前移
        //    (去掉一个选手后,前面的名额往前推;只弃权不顶替时,末尾空位即轮空)
        if (withdrawnCompetitorId != null) {
            TCompetitor w = competitorMapper.selectById(withdrawnCompetitorId);
            if (w == null || !w.getStageId().equals(stageId)) {
                throw new ServiceException("弃权者不存在或不属于本赛段");
            }
            String status = w.getOutcomeStatus();
            if (!OutcomeStatusEnum.ADVANCE.getCode().equals(status)
                && !OutcomeStatusEnum.WITHDRAWN.getCode().equals(status)) {
                throw new ServiceException("弃权者当前状态[{}]不允许弃权,仅晋级者(ADVANCE)可弃权", status);
            }
            if (OutcomeStatusEnum.ADVANCE.getCode().equals(status)) {
                Long removedRank = w.getFinalRank();
                markManualAdvanceResult(withdrawnCompetitorId, OutcomeStatusEnum.WITHDRAWN.getCode(),
                    w.getFinalRank(), stageId);
                if (removedRank != null) {
                    slideAdvancersForward(stageId, removedRank);
                }
            }
            log.info("海选赛段[{}]晋级者[{}]弃权(不占晋级名额)", stageId, withdrawnCompetitorId);
        }
        // 2) 顶替:把任意被淘汰的选手补到晋级名单末尾(名额往前推后空出的位置)
        int promoted = 0;
        if (replacementCompetitorId != null) {
            long advCount = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
            int advanceCount = readStageAdvanceCount(stage);
            if (advCount >= advanceCount) {
                throw new ServiceException("当前晋级名额已满({}),请先弃权一名晋级者后再顶替", advanceCount);
            }
            TCompetitor r = competitorMapper.selectById(replacementCompetitorId);
            if (r == null || !r.getStageId().equals(stageId)) {
                throw new ServiceException("顶替者不存在或不属于本赛段");
            }
            if (!OutcomeStatusEnum.ELIMINATED.getCode().equals(r.getOutcomeStatus())) {
                throw new ServiceException("顶替者当前状态[{}]不允许顶替,仅淘汰者(ELIMINATED)可顶上",
                    r.getOutcomeStatus());
            }
            long maxAdv = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, stageId)
                    .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
                    .select(TCompetitor::getFinalRank))
                .stream().mapToLong(c -> c.getFinalRank() == null ? 0L : c.getFinalRank())
                .max().orElse(0L);
            long newRank = maxAdv + 1; // 补齐到晋级名单末尾
            markManualAdvanceResult(replacementCompetitorId, OutcomeStatusEnum.ADVANCE.getCode(), newRank, stageId);
            promoted = 1;
            log.info("海选赛段[{}]淘汰者[{}]顶替晋级(finalRank={})", stageId, replacementCompetitorId, newRank);
        }
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return promoted;
    }

    /** 弃权后把其后的晋级者名次整体前移一位(名额往前推,保持名单连续) */
    private void slideAdvancersForward(Long stageId, Long removedRank) {
        List<TCompetitor> advancers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
            .select(TCompetitor::getId, TCompetitor::getFinalRank));
        for (TCompetitor c : advancers) {
            if (c.getFinalRank() != null && c.getFinalRank() > removedRank) {
                competitorMapper.update(null, Wrappers.<TCompetitor>lambdaUpdate()
                    .set(TCompetitor::getFinalRank, c.getFinalRank() - 1)
                    .eq(TCompetitor::getId, c.getId()));
            }
        }
        log.info("赛段[{}]移除名次[{}]后,其后晋级者名次前移", stageId, removedRank);
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
