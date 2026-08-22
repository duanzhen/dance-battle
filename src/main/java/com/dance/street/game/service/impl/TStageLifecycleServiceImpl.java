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
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.AddGuestBo;
import com.dance.street.game.domain.bo.CalculateAdvancementBo;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.InsertStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.CircleAssignVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageVo;
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
import com.dance.street.game.engine.common.enums.TransitionModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.MatchPlan;
import com.dance.street.game.engine.generator.SlotPlan;
import com.dance.street.game.engine.generator.StageGeneratorFactory;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.ScoringEngine;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITCompetitorMemberService;
import com.dance.street.game.service.ITCompetitorService;
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
        // 选拔赛/排名赛:逐选手轮次,允许跳过显式初始化(兜底:自动初始化)
        boolean perCompetitorRound = isAudition || isRank;
        if (!perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段尚未初始化,请先 initialize");
        }
        // 选拔赛/排名赛允许跳过显式初始化(兜底:自动初始化)
        if (perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            InitializeStageBo initBo = new InitializeStageBo();
            initBo.setStageId(stage.getId());
            initialize(initBo);
        }
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
        if (exist > 0) {
            throw new ServiceException("赛段对阵已生成,如需重新生成请先清除");
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
        // 承接上一淘汰赛胜者:按胜者位置顺序配对(SEQUENTIAL),不受本赛段 SEED 配置影响;
        // 从海选赛进入的淘汰赛,未显式配置时默认标准种子对位(1-16、2-15)
        if (StageModeEnum.KNOCKOUT.equals(mode)
            && rc != null && rc.getKnockout() != null) {
            TStage prev = stage.getPrevStageId() != null ? stageMapper.selectById(stage.getPrevStageId()) : null;
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

        // 按种子顺位取参赛方(选拔赛按签到号码顺序)
        LambdaQueryWrapper<TCompetitor> cq = Wrappers.lambdaQuery();
        cq.eq(TCompetitor::getStageId, stage.getId());
        if (perCompetitorRound) {
            cq.orderByAsc(TCompetitor::getNumber);
        } else {
            cq.orderByAsc(TCompetitor::getSeedRank);
        }
        List<TCompetitor> comps = competitorMapper.selectList(cq);
        // 保留原始种子位置:按 seedRank 落位,跳过场次/缺位留空,避免后续胜者抢占被跳过场次的位置
        long maxSeed = comps.stream().map(TCompetitor::getSeedRank).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        int slotCount = (int) Math.max(comps.size(), Math.min(maxSeed, 4096L));
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

        // 淘汰赛轮次序号:沿赛段链从第一个淘汰赛开始计数,用于场次命名 R{轮次}-M{场次}
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

            // 选拔赛/排名赛:每个选手一个轮次,按上场顺序
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
                TMatchParticipant p = new TMatchParticipant();
                p.setTournamentId(stage.getTournamentId());
                p.setMatchId(m.getId());
                p.setCompetitorId(slot.getCompetitorId());
                p.setDisplaySlotIndex((long) slot.getSlotIndex());
                p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
                participantMapper.insert(p);
            }
        }

        // 第二遍:按下游引用回填 promotion_rule(用真实 matchId)。选拔赛跳过,由 completeStage 结算晋级
        if (!perCompetitorRound) {
            for (MatchPlan mp : sorted) {
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

        log.info("赛段[{}]生成对阵完成:bracketSize={}, 场次数={}", stage.getId(), plan.getBracketSize(), sorted.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
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
            List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stageId).select(TMatch::getId))
                .stream().map(TMatch::getId).toList();
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
            }
            matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds));
            participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
            matchMapper.delete(Wrappers.<TMatch>lambdaQuery().in(TMatch::getId, matchIds));
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
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setCompetitorId(winnerCompetitorId);
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, winnerTarget.getTargetMatchId())
                .eq(TMatchParticipant::getDisplaySlotIndex, winnerTarget.getTargetSlot().longValue()));
        }
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
     * 每场对决:胜者留在队首,败者排到队尾,其余保持相对顺序。
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
            // 开始赛段即正式确认上一赛段晋级选手(预排 → 本赛段参赛方)
            CalculateAdvancementBo ab = new CalculateAdvancementBo();
            ab.setStageId(prev.getId());
            // 应用中间态手动调整的种子覆盖(ruleConfig.transition.seedOverrides)
            RuleConfigHolder prevRc = RuleConfigParser.parse(prev.getRuleConfig());
            if (prevRc != null && prevRc.getTransition() != null
                && prevRc.getTransition().getSeedOverrides() != null
                && !prevRc.getTransition().getSeedOverrides().isEmpty()) {
                ab.setSeedOverrides(prevRc.getTransition().getSeedOverrides());
            }
            calculateAdvancement(ab);
            // 晋级写入后重新读取本赛段(参赛方/状态可能已变化)
            stage = mustGetStage(stageId);
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
    public void appendAuditionCompetitor(Long stageId, Long competitorId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = mustGetStage(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || !StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
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
        // 选择当前人数最少的圈,尽量保持各圈均衡
        TMatch target = null;
        long minCount = Long.MAX_VALUE;
        for (TMatch m : candidates) {
            long cnt = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId()));
            if (cnt < minCount) {
                minCount = cnt;
                target = m;
            }
        }
        if (target == null) {
            return;
        }
        appendParticipantWithRound(target, competitorId);
        log.info("海选赛段[{}]补签到:参赛方[{}]挂入场次[{}]", stageId, competitorId, target.getId());
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TCompetitorVo addGuest(AddGuestBo bo) {
        TStage stage = mustGetStage(bo.getStageId());
        // 嘉宾禁止加入海选(海选走签到/补签到流程)
        if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("海选赛段不支持嘉宾加入");
        }
        // 仅赛段规划/未开始态(DRAFT/PENDING)且未初始化可加入:
        // 名单锁定(initialize)后对阵随之生成,中途加入的嘉宾将无法按抽签结果排位
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())
            && !StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            throw new ServiceException("仅赛段规划/未开始状态(DRAFT/PENDING)可加入嘉宾,当前状态: {}", stage.getStatus());
        }
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法再加入嘉宾");
        }
        if (bo.getName() == null || bo.getName().isBlank()) {
            throw new ServiceException("嘉宾名称不能为空");
        }

        // 1. 创建参赛单位:嘉宾标记 GUEST、种子排到队尾
        TCompetitorBo cbo = new TCompetitorBo();
        cbo.setTournamentId(stage.getTournamentId());
        cbo.setStageId(stage.getId());
        cbo.setType(bo.getType() == null ? 0L : bo.getType());
        cbo.setName(bo.getName().trim());
        cbo.setNumber(StringUtils.isNotBlank(bo.getNumber()) ? bo.getNumber().trim() : nextGuestNumber(stage));
        cbo.setSeedRank(nextSeedRank(stage));
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

        // 3. 不自动挂入任何场次:嘉宾先进入参赛方池,由导播按外部抽签结果设定种子顺序,
        //    之后 initialize → generateMatches 会连同既有晋级者一起生成对阵
        //    (嘉宾胜出即按赛段晋级名额正常占位)

        log.info("嘉宾[{}](id={})加入赛段[{}]({})", bo.getName().trim(), competitorId, stage.getId(), stage.getStageMode());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo insertGuestStage(InsertStageBo bo) {
        TStage a = mustGetStage(bo.getStageId());
        if (StageConstants.STAGE_DISCARD.equals(a.getStatus())) {
            throw new ServiceException("赛段[{}]已取消,不能在其后插入赛段", a.getName());
        }
        Long bId = a.getNextStageId();
        if (bId == null) {
            throw new ServiceException("赛段[{}]已是最后一环,其后无需插入赛段", a.getName());
        }
        TStage b = stageMapper.selectById(bId);
        if (b == null) {
            throw new ServiceException("赛段[{}]的下一赛段不存在", a.getName());
        }
        // 幂等:下一赛段已是插入的嘉宾赛段时拒绝重复插入
        if (StageConstants.GUEST_INSERT_REMARK.equals(b.getRemark())) {
            throw new ServiceException("赛段[{}]后已插入过嘉宾赛段,不可重复插入", a.getName());
        }
        // 窗口校验:下一赛段必须干净(无参赛方、未生成对阵、未结束),即"干净才让插"
        long compCnt = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, b.getId()));
        if (compCnt > 0) {
            throw new ServiceException("下一赛段[{}]已接收参赛方,无法插入嘉宾赛段(请在晋级发生前插入)", b.getName());
        }
        long matchCnt = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, b.getId()));
        if (matchCnt > 0) {
            throw new ServiceException("下一赛段[{}]已生成对阵,无法插入嘉宾赛段", b.getName());
        }
        if (StageConstants.STAGE_SETTLED.equals(b.getStatus())
            || StageConstants.STAGE_DISCARD.equals(b.getStatus())) {
            throw new ServiceException("下一赛段[{}]已结束,无法插入嘉宾赛段", b.getName());
        }

        // 晋级名额:默认取原下一赛段起始人数,可由导播显式指定
        long advance = bo.getAdvanceCount() != null && bo.getAdvanceCount() > 0
            ? bo.getAdvanceCount()
            : (b.getTeamCountStart() != null && b.getTeamCountStart() > 0 ? b.getTeamCountStart() : 0L);
        if (advance <= 0) {
            throw new ServiceException("无法确定晋级名额,请显式指定 advanceCount");
        }
        long start = a.getTeamCountEnd() != null ? a.getTeamCountEnd() : 0L;

        TStageBo sb = new TStageBo();
        sb.setTournamentId(a.getTournamentId());
        sb.setName(bo.getName().trim());
        sb.setStageMode(StageModeEnum.KNOCKOUT.getCode());
        sb.setTeamCountStart(start);
        sb.setTeamCountEnd(advance);
        sb.setStatus(StageConstants.STAGE_DRAFT);
        sb.setIsInitialized(0L);
        sb.setRemark(StageConstants.GUEST_INSERT_REMARK);
        sb.setPrevStageId(a.getId());
        sb.setNextStageId(b.getId());
        sb.setRuleConfig(buildGuestStageRuleConfig(start, advance));
        // 复用赛段服务的链表维护:自动把 A.next 指向新赛段、B.prev 指向新赛段
        TStageVo vo = stageService.insertByBo(sb);
        log.info("插入嘉宾赛段[{}](id={})于赛段[{}]与[{}]之间,晋级名额={}", vo.getName(), vo.getId(), a.getName(), b.getName(), advance);
        tournamentEventNotifier.notify(a.getTournamentId(), vo.getId(), null, "stage");
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeGuestStage(Long stageId) {
        TStage s = mustGetStage(stageId);
        if (!StageConstants.GUEST_INSERT_REMARK.equals(s.getRemark())) {
            throw new ServiceException("仅支持撤销插入的嘉宾赛段");
        }
        if (StageConstants.STAGE_SETTLED.equals(s.getStatus())
            || StageConstants.STAGE_DISCARD.equals(s.getStatus())) {
            throw new ServiceException("赛段[{}]已结束,无法撤销插入", s.getName());
        }
        long started = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .ne(TMatch::getStatus, StageConstants.MATCH_PENDING));
        if (started > 0) {
            throw new ServiceException("赛段[{}]已有场次开始,无法撤销插入", s.getName());
        }

        // 清理赛段数据:打分明细 → 轮次 → 参赛记录 → 场次 → 参赛方(含成员关联)
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (!matchIds.isEmpty()) {
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
            }
            matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds));
            participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
            matchMapper.delete(Wrappers.<TMatch>lambdaQuery().in(TMatch::getId, matchIds));
        }
        List<Long> compIds = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId).select(TCompetitor::getId))
            .stream().map(TCompetitor::getId).toList();
        if (!compIds.isEmpty()) {
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds));
            competitorMapper.delete(Wrappers.<TCompetitor>lambdaQuery().in(TCompetitor::getId, compIds));
        }

        // 删除赛段并恢复链表(A→B),复用赛段服务的前后节点重连
        stageService.deleteWithValidByIds(List.of(stageId), true);
        log.info("撤销插入的嘉宾赛段[{}](id={})", s.getName(), stageId);
        tournamentEventNotifier.notify(s.getTournamentId(), stageId, null, "stage");
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

    /** 嘉宾赛段默认规则配置:单轮淘汰、按种子顺序相邻配对(SEQUENTIAL),胜者按名额晋级下一赛段 */
    private String buildGuestStageRuleConfig(long start, long advance) {
        return "{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\","
            + "\"scoring\":{\"type\":\"WIN_LOSS_DRAW\",\"matchMode\":\"STANDARD\"},"
            + "\"knockout\":{\"teamsCount\":" + start
            + ",\"pairingMode\":\"SEQUENTIAL\",\"singleRound\":true,\"advanceCount\":" + advance + "},"
            + "\"transition\":{\"mode\":\"AUTO\",\"reshuffle\":false}}";
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

    /** 嘉宾选手号:留空时按本赛段最大数字型选手号 +1 生成,前缀 G 与常规选手区分 */
    private String nextGuestNumber(TStage stage) {
        long maxNum = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getNumber))
            .stream().map(TCompetitor::getNumber)
            .filter(n -> n != null && n.matches("\\d+"))
            .mapToLong(Long::parseLong)
            .max().orElse(0L);
        return "G" + (maxNum + 1);
    }

    /** 下一可用种子顺位(嘉宾排到队尾) */
    private long nextSeedRank(TStage stage) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getSeedRank))
            .stream().mapToLong(c -> c.getSeedRank() == null ? 0L : c.getSeedRank())
            .max().orElse(0L) + 1L;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeStage(Long stageId) {
        TStage stage = mustGetStage(stageId);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("仅 GAMING 状态的赛段可完成,当前: {}", stage.getStatus());
        }
        // 擂台赛:对决逐场独立结算,仅校验无进行中对决,不参与累计打分结算
        if (StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            long gaming = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
            if (gaming > 0) {
                throw new ServiceException("仍有 {} 场对决进行中,请先完成或重启后再结束赛段", gaming);
            }
        } else if (StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            // 选拔赛:最终结算,聚合所有裁判打分并排名晋级。之后场次变 SETTLED
            settleAuditionStage(stage);
            // 检查是否还有加赛场次未完成
            long tbUnfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (tbUnfinished > 0) {
                log.info("赛段[{}]存在{}场加赛未完成,等待加赛结束后再次结算", stageId, tbUnfinished);
                return; // 不抛异常,等待加赛结束后再次调用 completeStage
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
                return;
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
        // 不再自动晋级:裁判判完仅出预排,由导播台点击下一赛段「开始赛段」时正式确认选手
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

        List<TCompetitor> advancers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
            .orderByAsc(TCompetitor::getFinalRank));
        if (advancers.isEmpty()) {
            return 0;
        }

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        boolean reshuffle = rc != null && rc.getTransition() != null
            && Boolean.TRUE.equals(rc.getTransition().getReshuffle());
        Map<Long, Long> overrides = bo.getSeedOverrides();

        List<TCompetitor> ordered = new ArrayList<>(advancers);
        if (reshuffle) {
            Collections.shuffle(ordered);
        }
        // 保留原始场次位置(finalRank)作为下一赛段种子位,跳过场次留空不压缩
        Set<Long> usedSeeds = new HashSet<>();
        if (!reshuffle) {
            for (TCompetitor src : ordered) {
                if (src.getFinalRank() != null) {
                    usedSeeds.add(src.getFinalRank());
                }
            }
        }
        long nextFreeSeed = 1L;
        for (int i = 0; i < ordered.size(); i++) {
            TCompetitor src = ordered.get(i);
            Long override = overrides == null ? null : overrides.get(src.getId());
            long seed;
            if (override != null) {
                seed = override;
            } else if (!reshuffle && src.getFinalRank() != null) {
                seed = src.getFinalRank();
            } else {
                while (usedSeeds.contains(nextFreeSeed)) {
                    nextFreeSeed++;
                }
                seed = nextFreeSeed++;
            }
            usedSeeds.add(seed);
            TCompetitor nc = new TCompetitor();
            // 裁判端提交触发自动晋级时无登录租户上下文,需显式带租户,否则 tenant_id 插入报错
            nc.setTenantId(stage.getTenantId());
            nc.setTournamentId(stage.getTournamentId());
            nc.setStageId(next.getId());
            nc.setSourceCompetitorId(src.getId());
            nc.setType(src.getType());
            nc.setName(src.getName());
            nc.setNumber(src.getNumber());
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

    private void triggerAutoAdvancement(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        TransitionConfig tc = rc != null ? rc.getTransition() : null;
        String mode = TransitionModeEnum.fromCode(tc != null ? tc.getMode() : null).getCode();
        if (!TransitionModeEnum.AUTO.getCode().equals(mode)) {
            return;
        }
        if (resolveNextStageId(stage) == null) {
            return;
        }
        CalculateAdvancementBo ab = new CalculateAdvancementBo();
        ab.setStageId(stage.getId());
        calculateAdvancement(ab);
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

        for (List<Long> gMatchIds : groupMatchIds.values()) {
            java.util.Set<Long> gSet = new java.util.HashSet<>(gMatchIds);
            // competitorId -> 累计积分
            Map<Long, Integer> points = new HashMap<>();
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() == null || !gSet.contains(p.getMatchId())) {
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
            List<Long> ranked = new ArrayList<>(points.keySet());
            ranked.sort((a, b) -> Integer.compare(points.get(b), points.get(a)));
            for (int i = 0; i < ranked.size(); i++) {
                Long cid = ranked.get(i);
                TCompetitor cupd = new TCompetitor();
                cupd.setId(cid);
                cupd.setFinalRank((long) (i + 1));
                cupd.setOutcomeStatus(i < advancePerGroup
                    ? OutcomeStatusEnum.ADVANCE.getCode()
                    : OutcomeStatusEnum.ELIMINATED.getCode());
                competitorMapper.updateById(cupd);
            }
        }
    }

    /**
     * 选拔赛结算:取赛场所有参赛方的当前总分(由多裁判累计提交后写在 participant.scoreValue),
     * 按分数降序排名,前 advanceCount 名标 ADVANCE,其余标 ELIMINATED。
     */
    private void settleAuditionStage(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int advanceCount = 1;
        if (rc != null) {
            if (rc.getKnockout() != null && rc.getKnockout().getAdvanceCount() != null) {
                advanceCount = rc.getKnockout().getAdvanceCount();
            }
        }
        // 从 ruleConfig 的顶层读取 advanceCount(前端默认扁平结构)
        try {
            Map<String, Object> raw = new tools.jackson.databind.ObjectMapper().readValue(stage.getRuleConfig(), Map.class);
            if (raw.containsKey("advanceCount")) {
                advanceCount = ((Number) raw.get("advanceCount")).intValue();
            }
        } catch (Exception ignored) {
        }

        // 分圈:每圈独立晋级,总晋级数按圈均分
        int circles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
        if (circles > 1 && advanceCount % circles != 0) {
            throw new ServiceException("海选总晋级数[{}]无法按[{}]圈均分,请调整晋级名额或圈数", advanceCount, circles);
        }
        int perCircle = circles > 1 ? advanceCount / circles : advanceCount;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }

        // 圈序号(displayRow 顺序):决定每圈晋级的全局排名段
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        int ordinal = 0;
        for (TMatch m : matches) {
            String zone = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            zoneOrdinal.putIfAbsent(zone, ordinal++);
        }
        // 圈内已晋级数(含已结算正式圈与加赛,支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countAuditionAdvancedByZone(stage, matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            settleAuditionMatch(match, perCircle, zoneOrdinal, zoneAdvanced);
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
                                     Map<String, Integer> zoneOrdinal,
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

        // 剩余晋级名额:按圈独立计算,加赛场次只争本圈尚未确定的晋级位
        String zone = match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
        int zoneIndex = zoneOrdinal.getOrDefault(zone, 0);
        int zoneBase = zoneIndex * advanceQuota; // 本圈晋级选手的全局排名起点
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);

        Map<Long, java.math.BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : parts) {
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
            log.info("选拔赛场次[{}]结算:晋级名额已满,{}名同分选手淘汰", match.getId(), sortedCids.size());
            return;
        }

        // 检测晋级线上的同分情况
        if (remaining < sortedCids.size()) {
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
                log.info("选拔赛场次[{}]出现{}名同分选手,已创建加赛", match.getId(), tiedAtCutoff.size());
                return;
            }
        }

        // 无同分问题:正常结算
        for (int i = 0; i < sortedCids.size(); i++) {
            Long cid = sortedCids.get(i);
            boolean advance = i < remaining;
            markAuditionResult(cid, advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
        }
        zoneAdvanced.put(zone, alreadyAdvanced + Math.min(remaining, sortedCids.size()));

        TMatch mUpd = new TMatch();
        mUpd.setId(match.getId());
        mUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchMapper.updateById(mUpd);

        log.info("选拔赛场次[{}]已结算,共{}名选手,晋级{}名", match.getId(), sortedCids.size(),
            Math.min(remaining, sortedCids.size()));
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

        log.info("加赛场次[{}]已创建并自动开始,{}名选手参与", tb.getId(), tiedCompetitorIds.size());
    }

    /**
     * 排名赛结算:按圈聚合全部裁判×维度打分,计算每圈总分排名,
     * 晋级线内正常晋级;晋级线上同分并列导致名额超限时,同分者保持 PENDING,
     * 由导播台在中间态手动指定晋级者(adjustAdvancement)。
     */
    private void settleRankStage(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int advanceCount = 1;
        if (rc != null) {
            if (rc.getKnockout() != null && rc.getKnockout().getAdvanceCount() != null) {
                advanceCount = rc.getKnockout().getAdvanceCount();
            }
        }
        // 从 ruleConfig 顶层读取 advanceCount(前端默认扁平结构)
        try {
            Map<String, Object> raw = new tools.jackson.databind.ObjectMapper().readValue(stage.getRuleConfig(), Map.class);
            if (raw.containsKey("advanceCount")) {
                advanceCount = ((Number) raw.get("advanceCount")).intValue();
            }
        } catch (Exception ignored) {
        }

        // 分圈:每圈独立晋级,总晋级数按圈均分
        int circles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
        if (circles > 1 && advanceCount % circles != 0) {
            throw new ServiceException("排名赛总晋级数[{}]无法按[{}]圈均分,请调整晋级名额或圈数", advanceCount, circles);
        }
        int perCircle = circles > 1 ? advanceCount / circles : advanceCount;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }

        // 圈序号(displayRow 顺序):决定每圈晋级的全局排名段
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        int ordinal = 0;
        for (TMatch m : matches) {
            String zone = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            zoneOrdinal.putIfAbsent(zone, ordinal++);
        }
        // 圈内已晋级数(支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countRankAdvancedByZone(stage, matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            settleRankMatch(match, rc, perCircle, zoneOrdinal, zoneAdvanced);
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
                                 Map<String, Integer> zoneOrdinal, Map<String, Integer> zoneAdvanced) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId())
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            markMatchSettled(match);
            return;
        }
        List<Long> competitorIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();

        // 分数分布在各自轮次,跨本场全部轮次汇总
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
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
        int zoneIndex = zoneOrdinal.getOrDefault(zone, 0);
        int zoneBase = zoneIndex * advanceQuota;
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
        if (!StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅排名赛赛段支持手动调整同分晋级");
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
