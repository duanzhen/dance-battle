package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.ParticipantResultVo;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.RankCalculator;
import com.dance.street.game.engine.scoring.ScoringEngine;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 比赛结果提交编排:写明细分 → ScoringEngine 算分算排名 → 回写 participant →
 * 赛制特有后处理(淘汰填下游占位/标决赛胜者晋级)→ 场次 SETTLED → 可选自动 complete 赛段。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TMatchResultServiceImpl implements ITMatchResultService {

    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final ITStageLifecycleService stageLifecycleService;
    private final ITScoredMatchService scoredMatchService;
    private final com.dance.street.game.service.ITRefereeStageService refereeStageService;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ScoringEngine scoringEngine = new ScoringEngine();
    private static final tools.jackson.databind.ObjectMapper RESULT_MAPPER = new tools.jackson.databind.ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MatchResultVo submitResult(SubmitResultBo bo) {
        TMatch match = matchMapper.selectById(bo.getMatchId());
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (!StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            throw new ServiceException("场次当前状态[{}]不可提交结果,仅 GAMING 可提交", match.getStatus());
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        ScoringConfig sc = rc != null ? rc.getScoring() : null;
        MatchModeEnum mode = MatchModeEnum.fromCode(match.getMatchMode());

        // 安全:打分明细里的 refereeId 一律以服务端确定的 bo.refereeId 为准,
        // 清除客户端逐条提交的 refereeId,防止冒用其他裁判身份投票/打分
        if (bo.getScores() != null) {
            for (ScoreEntryBo se : bo.getScores()) {
                se.setRefereeId(null);
            }
        }

        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId()));
        List<Long> competitorIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();

        // 海选赛/排名赛(AUDITION/RANK): 多裁判累计打分,不即时结算。重复提交以最新为准(先删旧再写新)
        boolean isAudition = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode());
        boolean isRank = StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        boolean perCompetitor = isAudition || isRank;
        boolean isArena = StageModeEnum.ARENA.getCode().equals(stage.getStageMode());
        // 擂台赛:1v1 判胜负平,必须给出双方判定(胜+负 / 负+胜 / 平+平);
        // 平局时双方均排到队尾,由 computeArenaQueue 回放处理
        if (isArena && (bo.getOutcomes() == null || bo.getOutcomes().isEmpty())) {
            throw new ServiceException("擂台赛须提交胜负或平局判定");
        }
        // 多裁判累计打分(VOTING/RANKING,非逐选手轮次):只累计不结算,由 completeStage 统一结算
        if (!perCompetitor && (mode == MatchModeEnum.VOTING || mode == MatchModeEnum.RANKING)) {
            List<MatchScoreResult> accumulated = scoredMatchService.accumulateScores(match, stage, bo);
            return buildVo(match.getId(), StageConstants.MATCH_GAMING, accumulated);
        }
        // 海选赛/排名赛:逐选手 upsert(逐个提交/回改互不影响),提交后立即累计回显,不结算
        if (perCompetitor) {
            List<TMatchRound> auditionRounds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()));
            Map<Long, TMatchRound> roundByCompetitor = new HashMap<>();
            for (TMatchRound r : auditionRounds) {
                if (r.getCompetitorId() != null) {
                    roundByCompetitor.putIfAbsent(r.getCompetitorId(), r);
                }
            }
            Long refId = bo.getRefereeId() != null ? bo.getRefereeId() : 0L;
            if (bo.getScores() != null) {
                // 按 (轮次,选手) 分组:同一选手多个维度一次删除再批量插入,
                // 避免逐条「先删后插」导致同一次提交的多维度分互相覆盖
                Map<String, List<ScoreEntryBo>> byRoundCompetitor = new java.util.LinkedHashMap<>();
                for (ScoreEntryBo se : bo.getScores()) {
                    if (se.getCompetitorId() == null || se.getScore() == null) {
                        continue;
                    }
                    // 分数校验:海选按赛段配置满分校验(默认 10 分制,可配置 100 分制);
                    // 排名赛按维度满分校验(未配置时默认 100)
                    java.math.BigDecimal maxScore = isAudition
                        ? (rc != null && rc.getMaxScore() != null
                            ? rc.getMaxScore() : java.math.BigDecimal.valueOf(10))
                        : rankMaxScore(sc, se.getDimension());
                    if (se.getScore().compareTo(java.math.BigDecimal.ZERO) < 0
                        || se.getScore().compareTo(maxScore) > 0) {
                        throw new ServiceException("{}打分须在 0-{} 之间", isAudition ? "海选" : "维度", maxScore);
                    }
                    // 越界校验:只能给本场参赛方打分(海选/排名赛逐选手轮次);
                    // 加赛场次等单轮共评场景无选手专属轮次,回退到当前轮
                    if (se.getCompetitorId() == null || !competitorIds.contains(se.getCompetitorId())) {
                        throw new ServiceException("选手[{}]不属于本场,无法提交打分", se.getCompetitorId());
                    }
                    TMatchRound target = roundByCompetitor.get(se.getCompetitorId());
                    if (target == null) {
                        target = mustGetRound(match);
                    }
                    byRoundCompetitor
                        .computeIfAbsent(target.getId() + ":" + se.getCompetitorId(), k -> new ArrayList<>())
                        .add(se);
                }
                for (Map.Entry<String, List<ScoreEntryBo>> e : byRoundCompetitor.entrySet()) {
                    String[] key = e.getKey().split(":");
                    Long roundId = Long.valueOf(key[0]);
                    Long competitorId = Long.valueOf(key[1]);
                    roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                        .eq(TRoundScore::getRoundId, roundId)
                        .eq(TRoundScore::getRefereeId, refId)
                        .eq(TRoundScore::getCompetitorId, competitorId));
                    for (ScoreEntryBo se : e.getValue()) {
                        TRoundScore rs = new TRoundScore();
                        rs.setTournamentId(match.getTournamentId());
                        rs.setRoundId(roundId);
                        rs.setTenantId(match.getTenantId());
                        rs.setCompetitorId(competitorId);
                        rs.setRefereeId(refId);
                        rs.setScore(se.getScore());
                        rs.setDimension(StringUtils.isNotBlank(se.getDimension()) ? se.getDimension() : StageConstants.DIMENSION_MAIN);
                        rs.setAction(StringUtils.isNotBlank(se.getAction()) ? se.getAction() : StageConstants.SCORE_ACTION_SCORE);
                        roundScoreMapper.insert(rs);
                    }
                }
            }
            if (isAudition) {
                accumulateAuditionScores(match, competitorIds);
            } else {
                accumulateRankScores(match, stage, competitorIds);
            }
            List<MatchScoreResult> accumulated = loadAuditionResults(match, competitorIds);
            refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "scores");
            tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "scores");
            // 二海/三海:全员打分完成后自动结算(若再次同分会自动生成下一级加赛),
            // 无需裁判判完后手动再点一次完成赛段;结算异常不阻断打分提交(仍可手动完成兜底)
            if (isAudition) {
                try {
                    stageLifecycleService.tryAutoSettleTiebreaker(match.getId());
                } catch (Exception e) {
                    log.warn("海选加赛[{}]自动结算失败,可手动完成赛段兜底: {}", match.getId(), e.getMessage());
                }
            }
            // 排名赛 BATCH 公布模式:全部裁判对全部选手打分完成后,自动完成赛段一次性公布
            if (isRank) {
                maybeAutoPublishRankStage(stage);
            }
            return buildVo(match.getId(), StageConstants.MATCH_GAMING, accumulated);
        }

        // 写明细分 → TRoundScore(非海选)
        List<TRoundScore> rawScores = new ArrayList<>();
        if (bo.getScores() != null && !bo.getScores().isEmpty()) {
            TMatchRound round = mustGetRound(match);
            for (ScoreEntryBo se : bo.getScores()) {
                // 越界校验:只能给本场参赛方提交打分,避免向不相关选手写入脏数据
                if (se.getCompetitorId() == null || !competitorIds.contains(se.getCompetitorId())) {
                    throw new ServiceException("选手[{}]不属于本场,无法提交打分", se.getCompetitorId());
                }
                TRoundScore rs = new TRoundScore();
                rs.setTournamentId(match.getTournamentId());
                rs.setRoundId(round.getId());
                // 裁判端无登录租户上下文,需显式带租户,否则 tenant_id 插入报错
                rs.setTenantId(match.getTenantId());
                rs.setCompetitorId(se.getCompetitorId());
                rs.setRefereeId(bo.getRefereeId());
                rs.setScore(se.getScore());
                rs.setDimension(StringUtils.isNotBlank(se.getDimension()) ? se.getDimension() : StageConstants.DIMENSION_MAIN);
                rs.setAction(StringUtils.isNotBlank(se.getAction()) ? se.getAction() : StageConstants.SCORE_ACTION_SCORE);
                roundScoreMapper.insert(rs);
                rawScores.add(rs);
            }
        }

        // 结果公布模式:DIRECTOR 模式裁判无需判罚
        boolean isKnockoutStandard = StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())
            && MatchModeEnum.STANDARD.equals(mode);
        String publishMode = readPublishMode(stage);
        if (isKnockoutStandard && bo.getRefereeId() != null
            && "DIRECTOR".equalsIgnoreCase(publishMode)) {
            throw new ServiceException("本场由导播台判定,裁判无需判罚");
        }

        // 裁判判罚投票(STANDARD):裁判端提交只记一票并落库;
        // 多名裁判时全部判完才统一结算,单名裁判提交后立即结算
        Map<Long, String> effectiveOutcomes = bo.getOutcomes();
        boolean refereeVote = MatchModeEnum.STANDARD.equals(mode)
            && !perCompetitor
            && bo.getRefereeId() != null
            && !competitorIds.isEmpty()
            && !refereeStageService.getRefereeIdsByStageId(stage.getId()).isEmpty();
        if (refereeVote) {
            int assigned = refereeStageService.getRefereeIdsByStageId(stage.getId()).size();
            TMatchRound voteRound = mustGetRound(match);
            // 覆盖写本裁判本轮投票(WIN=1 / LOSS=0 / DRAW=0.5),不影响其他裁判
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, voteRound.getId())
                .eq(TRoundScore::getRefereeId, bo.getRefereeId())
                .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE));
            for (Long cid : competitorIds) {
                String o = bo.getOutcomes() == null ? null : bo.getOutcomes().get(cid);
                java.math.BigDecimal v = MatchOutcomeEnum.WIN.getCode().equals(o) ? java.math.BigDecimal.ONE
                    : MatchOutcomeEnum.DRAW.getCode().equals(o) ? new java.math.BigDecimal("0.5")
                    : MatchOutcomeEnum.LOSS.getCode().equals(o) ? java.math.BigDecimal.ZERO : null;
                if (v == null) {
                    continue;
                }
                TRoundScore vote = new TRoundScore();
                vote.setTournamentId(match.getTournamentId());
                vote.setRoundId(voteRound.getId());
                vote.setTenantId(match.getTenantId());
                vote.setCompetitorId(cid);
                vote.setRefereeId(bo.getRefereeId());
                vote.setScore(v);
                vote.setDimension(StageConstants.DIMENSION_MAIN);
                vote.setAction(StageConstants.SCORE_ACTION_VOTE);
                roundScoreMapper.insert(vote);
            }
            long voted = roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .eq(TRoundScore::getRoundId, voteRound.getId())
                    .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE)
                    .select(TRoundScore::getRefereeId))
                .stream().map(TRoundScore::getRefereeId).filter(Objects::nonNull).distinct().count();
            if (voted < assigned) {
                log.info("场次[{}]裁判[{}]已投票({}/{}),等待其他裁判", match.getId(), bo.getRefereeId(), voted, assigned);
                refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "scores");
                tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "scores");
                return buildVo(match.getId(), StageConstants.MATCH_GAMING, List.of());
            }
            Map<Long, String> aggregated = aggregateRefereeVotes(voteRound, competitorIds, assigned);
            if (aggregated == null) {
                // 票数持平(如 1红1蓝、1红1蓝1平):综合判定为平局。
                // 淘汰赛在当前场次下加赛一轮重新比;擂台赛直接以平局结算,
                // 擂主与挑战者均排到队尾,由 computeArenaQueue 回放处理
                log.info("场次[{}]裁判意见持平(左胜{} vs 右胜{}),判定平局{}",
                    match.getId(), leftWinVotes(voteRound, competitorIds),
                    rightWinVotes(voteRound, competitorIds), isArena ? "(擂台赛双方排到队尾)" : "并加赛一轮");
                aggregated = new HashMap<>();
                for (Long cid : competitorIds) {
                    aggregated.put(cid, MatchOutcomeEnum.DRAW.getCode());
                }
            }
            effectiveOutcomes = aggregated;
        }

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(mode)
            .scoringConfig(sc)
            .competitorIds(competitorIds)
            .rawScores(rawScores)
            .directOutcomes(effectiveOutcomes)
            .build();
        List<MatchScoreResult> results = scoringEngine.compute(input);

        // 淘汰赛 STANDARD 平局:不结算、不填下游,自动新增一轮加赛,等待再次判罚
        if (isKnockoutStandard && isDrawResult(competitorIds, results)) {
            return createReplayRound(match, results);
        }

        // 结果公布模式 MANUAL:裁判判完仅暂存结果,场次保持进行中,待导播台确认公布
        if (isKnockoutStandard && bo.getRefereeId() != null
            && "MANUAL".equalsIgnoreCase(publishMode)) {
            TMatch pending = new TMatch();
            pending.setId(match.getId());
            pending.setResultJson(toResultJson(effectiveOutcomes));
            matchMapper.updateById(pending);
            log.info("场次[{}]裁判已判完,结果待导播台公布: {}", match.getId(), effectiveOutcomes);
            refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "scores");
            tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "scores");
            return buildVo(match.getId(), StageConstants.MATCH_GAMING, List.of());
        }

        // 回写 participant
        for (MatchScoreResult r : results) {
            TMatchParticipant upd = new TMatchParticipant();
            upd.setScoreValue(r.getScoreValue());
            upd.setRankInMatch(r.getRankInMatch() == null ? null : r.getRankInMatch().longValue());
            upd.setOutcomeStatus(r.getOutcomeStatus());
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, r.getCompetitorId()));
        }

        // 淘汰特有:填下游占位 / 标决赛胜者晋级下一赛段
        if (StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            resolveKnockoutDownstream(match, results);
        }

        // 场次 SETTLED
        TMatch mUpd = new TMatch();
        mUpd.setId(match.getId());
        mUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchMapper.updateById(mUpd);
        // 场次已出结果,清理擂台重投计数,避免影响后续对决
        RedisUtils.deleteObject("arena:revote:" + match.getId());

        // 全部轮次(含平局加赛轮)一并结算
        TMatchRound roundUpd = new TMatchRound();
        roundUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
            .eq(TMatchRound::getMatchId, match.getId()));

        // 默认不自动 complete 赛段:最后一个场次结束后由导播台手动"完成赛段"(仅显式传 true 时自动)
        boolean auto = Boolean.TRUE.equals(bo.getFinalizeStageIfComplete());
        if (auto) {
            long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId()).ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (unfinished == 0) {
                stageLifecycleService.completeStage(stage.getId());
            }
        }
        refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "match");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "match");

        return buildVo(match.getId(), StageConstants.MATCH_SETTLED, results);
    }

    /**
     * 开始指定场次:PENDING → GAMING;赛段未开始时随场次进入 GAMING。
     * 其余场次保持 PENDING,可用来"跳过其他场次、先开始指定场次"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startMatch(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (!StageConstants.MATCH_PENDING.equals(match.getStatus())) {
            throw new ServiceException("仅 PENDING 场次可开始,当前: {}", match.getStatus());
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage == null
            || StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段状态不允许开始场次");
        }
        // 轮空场次(单边/双边轮空):无需裁判,导播台点「开始」后仅自动结算本场,直接返回
        long realCount = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matchId))
            .stream().filter(p -> p.getCompetitorId() != null).count();
        if (realCount <= 1) {
            if (StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
                TStage sUpd = new TStage();
                sUpd.setId(stage.getId());
                sUpd.setStatus(StageConstants.STAGE_GAMING);
                stageMapper.updateById(sUpd);
                refereeSseNotifier.notifyStage(stage.getId(), "stage");
                tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
            }
            boolean settled = stageLifecycleService.settleByeMatch(matchId);
            if (!settled) {
                // 0 参赛方的非首轮场次是等待上游胜者填入的占位,不能按轮空结算
                throw new ServiceException("该场次暂无参赛方,请先完成上一轮场次后再开始");
            }
            return;
        }
        // 淘汰赛逐场进行:同赛段其他进行中场次回退 PENDING 并清空已提交分数,保证同时只有一个进行中
        if (StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            List<TMatch> otherGaming = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, match.getStageId())
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
                .ne(TMatch::getId, match.getId()));
            for (TMatch other : otherGaming) {
                // 防误操作丢票:其他进行中场次已有裁判提交判罚时拒绝切换,
                // 要求先完成或显式重置,避免静默清空已投票
                List<Long> otherRoundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                        .eq(TMatchRound::getMatchId, other.getId())
                        .select(TMatchRound::getId))
                    .stream().map(TMatchRound::getId).toList();
                if (!otherRoundIds.isEmpty()) {
                    long voted = roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
                        .in(TRoundScore::getRoundId, otherRoundIds));
                    if (voted > 0) {
                        throw new ServiceException(
                            "场次[{}]已有裁判提交判罚,请先完成或重置该场次后再开始新场次", other.getId());
                    }
                }
                clearMatchState(other, StageConstants.MATCH_PENDING);
                refereeSseNotifier.notifyMatch(other.getStageId(), other.getId(), "match");
                tournamentEventNotifier.notify(other.getTournamentId(), other.getStageId(), other.getId(), "match");
                log.info("场次[{}]因开始场次[{}]回退待开始", other.getId(), matchId);
            }
        }
        // 赛段若尚未开始,随本场一起进入 GAMING
        if (StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            TStage sUpd = new TStage();
            sUpd.setId(stage.getId());
            sUpd.setStatus(StageConstants.STAGE_GAMING);
            stageMapper.updateById(sUpd);
            refereeSseNotifier.notifyStage(stage.getId(), "stage");
            tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        }
        TMatch mUpd = new TMatch();
        mUpd.setId(match.getId());
        mUpd.setStatus(StageConstants.MATCH_GAMING);
        matchMapper.updateById(mUpd);
        TMatchRound roundUpd = new TMatchRound();
        roundUpd.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
            .eq(TMatchRound::getMatchId, match.getId()));
        refereeSseNotifier.notifyMatch(stage.getId(), match.getId(), "match");
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), match.getId(), "match");
        log.info("场次[{}]已单独开始", matchId);
    }

    /**
     * 取消开始场次(误触回退):GAMING → PENDING,清空本场已提交分数/结果与轮次状态,
     * 用于导播台点错「开始」后还原为待开始(可重新开始正确场次)。
     * 仅淘汰赛支持:其他赛制场次由赛段整体控制,不支持单场回退待开始。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelStartMatch(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (!StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            throw new ServiceException("仅进行中的场次可取消开始,当前: {}", match.getStatus());
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage == null
            || StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段状态不允许取消开始场次");
        }
        if (!StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅淘汰赛支持取消开始场次");
        }
        // 清空本场已提交的分数/结果,场次与轮次回 PENDING(已提交判罚一并作废,由前端确认兜底)
        clearMatchState(match, StageConstants.MATCH_PENDING);
        refereeSseNotifier.notifyMatch(match.getStageId(), matchId, "reset");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "reset");
        log.info("场次[{}]已取消开始,回退待开始", matchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetMatch(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        // 赛段已结束/取消时禁止重启场次,避免出现「赛段 SETTLED + 场次 GAMING」的悬挂状态
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage != null && (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus()))) {
            throw new ServiceException("赛段[{}]已结束,无法重启场次", stage.getName());
        }
        // 已结算或进行中(有累计分)都可重启;进行中重启 = 清空已提交分数重新开始
        if (!StageConstants.MATCH_SETTLED.equals(match.getStatus())
            && !StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            throw new ServiceException("仅已结算或进行中的场次可重启,当前[{}]", match.getStatus());
        }
        if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
            // 级联:清本场胜者填入的下游占位(若下游已开赛则禁止)
            Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(match.getPromotionRule());
            PromotionTarget wt = rule.get("1");
            if (wt != null && StageConstants.ACTION_ADVANCE.equals(wt.getAction())
                && wt.getTargetMatchId() != null && wt.getTargetSlot() != null) {
                TMatch downstream = matchMapper.selectById(wt.getTargetMatchId());
                if (downstream != null && !StageConstants.MATCH_PENDING.equals(downstream.getStatus())) {
                    throw new ServiceException("下游场次已开赛,无法 reset");
                }
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, null)
                    .eq(TMatchParticipant::getMatchId, wt.getTargetMatchId())
                    .eq(TMatchParticipant::getDisplaySlotIndex, wt.getTargetSlot().longValue()));
            }
            // 季军赛:败者占位同样回退,避免 reset 后半决赛败者残留
            PromotionTarget lt = rule.get("2");
            if (lt != null && StageConstants.ACTION_ADVANCE.equals(lt.getAction())
                && lt.getTargetMatchId() != null && lt.getTargetSlot() != null) {
                TMatch downstream = matchMapper.selectById(lt.getTargetMatchId());
                if (downstream != null && !StageConstants.MATCH_PENDING.equals(downstream.getStatus())) {
                    throw new ServiceException("季军赛场次已开赛,无法 reset");
                }
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, null)
                    .eq(TMatchParticipant::getMatchId, lt.getTargetMatchId())
                    .eq(TMatchParticipant::getDisplaySlotIndex, lt.getTargetSlot().longValue()));
            }
        }
        clearMatchState(match, StageConstants.MATCH_GAMING);
        refereeSseNotifier.notifyMatch(match.getStageId(), matchId, "reset");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "reset");
        log.info("场次[{}]结果已 reset", matchId);
    }

    /**
     * 清空一场的已提交分数/结果并回退状态(round_score、participant 分数排名、
     * 参赛方 outcome/finalRank、防重提交 key),用于 reset 重启与淘汰赛切换当前场次。
     *
     * @param targetStatus 清空后场次目标状态(PENDING / GAMING)
     */
    private void clearMatchState(TMatch match, String targetStatus) {
        Long matchId = match.getId();
        // 清本场 roundScore
        List<TMatchRound> rounds = matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, matchId));
        if (!rounds.isEmpty()) {
            List<Long> roundIds = rounds.stream().map(TMatchRound::getId).toList();
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        // 清本场 participant 分数/排名/结果
        participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
            .set(TMatchParticipant::getScoreValue, null)
            .set(TMatchParticipant::getRankInMatch, null)
            .set(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.PENDING.getCode())
            .eq(TMatchParticipant::getMatchId, matchId));
        // 参赛方赛段结果一并回退 PENDING(重新判罚,避免旧晋级/淘汰残留)
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, matchId));
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                competitorMapper.update(null, Wrappers.<TCompetitor>lambdaUpdate()
                    .set(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode())
                    // 重判后晋级顺序一并清空,避免残留 finalRank 干扰下一赛段预排种子
                    .set(TCompetitor::getFinalRank, null)
                    .eq(TCompetitor::getId, p.getCompetitorId()));
            }
        }
        // 清掉本场 submit-result 的防重提交 key,重启后可立即重新判罚,避免 5 秒内重复提交被拦截
        RedisUtils.deleteKeys(GlobalConstants.REPEAT_SUBMIT_KEY + "/game/match/" + matchId + "/submit-result*");
        // 清擂台重投计数,重新开始后重新计
        RedisUtils.deleteObject("arena:revote:" + matchId);
        // 场次与轮次回目标状态
        TMatch mUpd = new TMatch();
        mUpd.setId(matchId);
        mUpd.setStatus(targetStatus);
        matchMapper.updateById(mUpd);
        TMatchRound roundUpd = new TMatchRound();
        roundUpd.setStatus(targetStatus);
        matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
            .eq(TMatchRound::getMatchId, matchId));
    }

    private void resolveKnockoutDownstream(TMatch match, List<MatchScoreResult> results) {
        // 淘汰赛单败:非胜者(败者)赛段级结果标记为淘汰
        for (MatchScoreResult r : results) {
            if (r.getCompetitorId() == null) {
                continue;
            }
            if (r.getRankInMatch() == null || r.getRankInMatch() > 1) {
                markCompetitorOutcome(r.getCompetitorId(), OutcomeStatusEnum.ELIMINATED.getCode());
            }
        }

        // 胜者(本场第 1 名)去向
        Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(match.getPromotionRule());
        PromotionTarget winnerTarget = rule.get("1");
        if (winnerTarget == null) {
            return;
        }
        MatchScoreResult winner = results.stream()
            .filter(r -> r.getRankInMatch() != null && r.getRankInMatch() == 1)
            .findFirst().orElse(null);
        if (winner == null || winner.getCompetitorId() == null) {
            return;
        }
        if (StageConstants.ACTION_FINAL_ADVANCE.equals(winnerTarget.getAction())) {
            // 决赛胜者:标记晋级下一赛段,并按场次顺序记录 finalRank(预排种子依据)
            markCompetitorAdvance(winner.getCompetitorId(), match);
        } else if (StageConstants.ACTION_ADVANCE.equals(winnerTarget.getAction())
            && winnerTarget.getTargetMatchId() != null && winnerTarget.getTargetSlot() != null) {
            // 填入下游场次占位:占位行缺失时补插(生成对阵时空槽不落 participant 行)
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setCompetitorId(winner.getCompetitorId());
            int affected = participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, winnerTarget.getTargetMatchId())
                .eq(TMatchParticipant::getDisplaySlotIndex, winnerTarget.getTargetSlot().longValue()));
            if (affected == 0) {
                TMatchParticipant np = new TMatchParticipant();
                np.setTenantId(match.getTenantId());
                np.setTournamentId(match.getTournamentId());
                np.setMatchId(winnerTarget.getTargetMatchId());
                np.setCompetitorId(winner.getCompetitorId());
                np.setDisplaySlotIndex(winnerTarget.getTargetSlot().longValue());
                np.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
                participantMapper.insert(np);
                log.info("场次[{}]胜者[{}]补插到下游场次[{}]占位(slot={})",
                    match.getId(), winner.getCompetitorId(), winnerTarget.getTargetMatchId(),
                    winnerTarget.getTargetSlot().longValue());
            }
        }

        // 季军赛:本场第 2 名(败者)路由到败者组场次(半决赛败者互争季军)
        PromotionTarget loserTarget = rule.get("2");
        if (loserTarget != null && StageConstants.ACTION_ADVANCE.equals(loserTarget.getAction())
            && loserTarget.getTargetMatchId() != null && loserTarget.getTargetSlot() != null) {
            MatchScoreResult loser = results.stream()
                .filter(r -> r.getRankInMatch() != null && r.getRankInMatch() == 2)
                .findFirst().orElse(null);
            if (loser != null && loser.getCompetitorId() != null) {
                fillDownstreamSlot(match, loserTarget.getTargetMatchId(),
                    loserTarget.getTargetSlot().longValue(), loser.getCompetitorId());
                log.info("场次[{}]败者[{}]补插到季军赛场次[{}]占位(slot={})",
                    match.getId(), loser.getCompetitorId(), loserTarget.getTargetMatchId(),
                    loserTarget.getTargetSlot().longValue());
            }
        }
    }

    /** 结果填入下游场次占位:占位行已存在则更新,不存在则补插(与胜者路由共用) */
    private void fillDownstreamSlot(TMatch sourceMatch, Long targetMatchId, Long targetSlot, Long competitorId) {
        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setCompetitorId(competitorId);
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
        np.setCompetitorId(competitorId);
        np.setDisplaySlotIndex(targetSlot);
        np.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(np);
    }

    private void markCompetitorOutcome(Long competitorId, String status) {
        TCompetitor cupd = new TCompetitor();
        cupd.setId(competitorId);
        cupd.setOutcomeStatus(status);
        competitorMapper.updateById(cupd);
    }

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

    private TMatchRound mustGetRound(TMatch match) {
        List<TMatchRound> rounds = matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, match.getId())
                .orderByAsc(TMatchRound::getRoundSequence));
        if (!rounds.isEmpty()) {
            // 多轮制/平局加赛时取最新一轮(当前生效轮),而非首轮
            return rounds.get(rounds.size() - 1);
        }
        TMatchRound round = new TMatchRound();
        round.setTournamentId(match.getTournamentId());
        round.setMatchId(match.getId());
        round.setTenantId(match.getTenantId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(round);
        return round;
    }

    /**
     * 汇总多裁判投票:全平 → 双 DRAW(触发加赛轮);左/右胜票多者胜;票数持平 → null(不结算,等待改判)。
     */
    private Map<Long, String> aggregateRefereeVotes(TMatchRound round, List<Long> competitorIds, int assigned) {
        List<TRoundScore> votes = roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId())
                .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE));
        Long left = competitorIds.size() > 0 ? competitorIds.get(0) : null;
        Long right = competitorIds.size() > 1 ? competitorIds.get(1) : null;
        int leftWins = 0, rightWins = 0, drawRows = 0;
        for (TRoundScore v : votes) {
            if (v.getScore() == null) {
                continue;
            }
            if (v.getScore().compareTo(java.math.BigDecimal.ONE) == 0) {
                if (Objects.equals(v.getCompetitorId(), left)) {
                    leftWins++;
                } else if (Objects.equals(v.getCompetitorId(), right)) {
                    rightWins++;
                }
            } else if (v.getScore().compareTo(new java.math.BigDecimal("0.5")) == 0) {
                drawRows++;
            }
        }
        // 每位裁判判平时会给两名选手各记一条 0.5
        int drawReferees = competitorIds.isEmpty() ? 0 : drawRows / competitorIds.size();
        Map<Long, String> out = new HashMap<>();
        if (drawReferees >= assigned) {
            for (Long cid : competitorIds) {
                out.put(cid, MatchOutcomeEnum.DRAW.getCode());
            }
            return out;
        }
        if (leftWins > rightWins) {
            out.put(left, MatchOutcomeEnum.WIN.getCode());
            out.put(right, MatchOutcomeEnum.LOSS.getCode());
            return out;
        }
        if (rightWins > leftWins) {
            out.put(left, MatchOutcomeEnum.LOSS.getCode());
            out.put(right, MatchOutcomeEnum.WIN.getCode());
            return out;
        }
        return null;
    }

    private int leftWinVotes(TMatchRound round, List<Long> competitorIds) {
        return winVotes(round, competitorIds.size() > 0 ? competitorIds.get(0) : null);
    }

    private int rightWinVotes(TMatchRound round, List<Long> competitorIds) {
        return winVotes(round, competitorIds.size() > 1 ? competitorIds.get(1) : null);
    }

    private int winVotes(TMatchRound round, Long competitorId) {
        if (round == null || competitorId == null) {
            return 0;
        }
        return roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId())
                .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE)
                .eq(TRoundScore::getCompetitorId, competitorId)
                .eq(TRoundScore::getScore, java.math.BigDecimal.ONE))
            .intValue();
    }

    /**
     * 结果公布模式:读淘汰赛 ruleConfig.knockout.publishMode,默认 AUTO。
     */
    private String readPublishMode(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc != null && rc.getKnockout() != null && StringUtils.isNotBlank(rc.getKnockout().getPublishMode())) {
            return rc.getKnockout().getPublishMode();
        }
        return "AUTO";
    }

    private String toResultJson(Map<Long, String> outcomes) {
        try {
            return RESULT_MAPPER.writeValueAsString(outcomes);
        } catch (Exception e) {
            throw new ServiceException("待公布结果序列化失败: {}", e.getMessage());
        }
    }

    private Map<Long, String> parseResultJson(String json) {
        try {
            Map<String, Object> raw = RESULT_MAPPER.readValue(json, Map.class);
            Map<Long, String> out = new HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                out.put(Long.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            throw new ServiceException("待公布结果解析失败: {}", e.getMessage());
        }
    }

    /**
     * 导播台确认公布结果(MANUAL 模式):用裁判判完暂存的结果结算场次。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MatchResultVo publishResult(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (!StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            throw new ServiceException("仅进行中场次可公布结果,当前: {}", match.getStatus());
        }
        if (StringUtils.isBlank(match.getResultJson())) {
            throw new ServiceException("该场暂无待公布结果,请等待裁判判罚完成");
        }
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setOutcomes(parseResultJson(match.getResultJson()));
        // 公布结果只结束场次,不自动完成赛段(由导播台手动"完成赛段")
        MatchResultVo vo = submitResult(bo);
        // 公布后清空暂存结果
        TMatch upd = new TMatch();
        upd.setId(matchId);
        upd.setResultJson(null);
        matchMapper.updateById(upd);
        log.info("场次[{}]结果已由导播台公布", matchId);
        return vo;
    }

    /**
     * 淘汰赛平局判定:所有参赛方结果均为 DRAW。
     */
    private boolean isDrawResult(List<Long> competitorIds, List<MatchScoreResult> results) {
        if (competitorIds == null || competitorIds.isEmpty()) {
            return false;
        }
        return competitorIds.stream().allMatch(cid -> results.stream()
            .filter(r -> Objects.equals(r.getCompetitorId(), cid))
            .findFirst()
            .map(r -> MatchOutcomeEnum.DRAW.getCode().equals(r.getOutcomeStatus()))
            .orElse(false));
    }

    /**
     * 淘汰赛平局加赛:本轮结果作废(参与方回退 PENDING),新增下一轮次,
     * 场次保持 GAMING,由裁判在加赛轮再次判罚。
     */
    private MatchResultVo createReplayRound(TMatch match, List<MatchScoreResult> results) {
        // 平局轮不产生正式结果,回退参与方为待判定
        participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
            .set(TMatchParticipant::getScoreValue, null)
            .set(TMatchParticipant::getRankInMatch, null)
            .set(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.PENDING.getCode())
            .eq(TMatchParticipant::getMatchId, match.getId()));

        TMatchRound drawnRound = mustGetRound(match);
        TMatchRound drawnUpd = new TMatchRound();
        drawnUpd.setId(drawnRound.getId());
        drawnUpd.setStatus(StageConstants.MATCH_SETTLED);
        matchRoundMapper.updateById(drawnUpd);

        // 新增加赛轮(下一轮序号),场次保持 GAMING
        TMatchRound replay = new TMatchRound();
        replay.setTournamentId(match.getTournamentId());
        replay.setMatchId(match.getId());
        replay.setTenantId(match.getTenantId());
        replay.setRoundSequence(drawnRound.getRoundSequence() + 1);
        replay.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(replay);

        log.info("场次[{}]判定平局,新增第{}轮加赛,等待再次判罚", match.getId(), replay.getRoundSequence());
        refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "draw");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "draw");
        return buildVo(match.getId(), StageConstants.MATCH_GAMING, results);
    }

    /**
     * 海选赛累计打分:从已写入的 TRoundScore 重新汇总每个参赛方的总分并回写 participant。
     * 比赛场次保持 GAMING,不结算。管理员最终通过 completeStage 结算排名。
     */
    private void accumulateAuditionScores(TMatch match, List<Long> competitorIds) {
        // 逐选手打分后分数分布在各自轮次,需跨本场全部轮次汇总
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (roundIds.isEmpty()) {
            return;
        }
        List<TRoundScore> allScores = roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));

        Map<Long, java.math.BigDecimal> totals = new HashMap<>();
        for (TRoundScore rs : allScores) {
            if (rs.getCompetitorId() == null || rs.getScore() == null) {
                continue;
            }
            totals.merge(rs.getCompetitorId(), rs.getScore(), java.math.BigDecimal::add);
        }

        for (Long cid : competitorIds) {
            java.math.BigDecimal sum = totals.getOrDefault(cid, java.math.BigDecimal.ZERO);
            participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                .set(TMatchParticipant::getScoreValue, sum)
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, cid));
        }
    }

    /**
     * 排名赛累计打分:跨本场全部轮次(每个选手一个轮次)聚合多裁判×多维度分,
     * 用 RANKING 策略重算每个参赛方的总分与排名并回写 participant(场次保持 GAMING)。
     */
    private void accumulateRankScores(TMatch match, TStage stage, List<Long> competitorIds) {
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (roundIds.isEmpty()) {
            return;
        }
        List<TRoundScore> allScores = roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.RANKING)
            .scoringConfig(rc != null ? rc.getScoring() : null)
            .competitorIds(competitorIds)
            .rawScores(allScores)
            .build();
        List<MatchScoreResult> results = scoringEngine.compute(input);
        for (MatchScoreResult r : results) {
            if (r.getCompetitorId() == null) {
                continue;
            }
            participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                .set(TMatchParticipant::getScoreValue, r.getScoreValue())
                .set(TMatchParticipant::getRankInMatch,
                    r.getRankInMatch() == null ? null : r.getRankInMatch().longValue())
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, r.getCompetitorId()));
        }
    }

    /** 排名赛维度满分:取打分配置中该维度 maxScore,未配置/未命中时默认 100 */
    private java.math.BigDecimal rankMaxScore(ScoringConfig sc, String dimension) {
        if (sc != null && sc.getDimensions() != null) {
            for (var d : sc.getDimensions()) {
                if (d.getKey() != null && d.getKey().equals(dimension) && d.getMaxScore() != null) {
                    return d.getMaxScore();
                }
            }
        }
        return java.math.BigDecimal.valueOf(100);
    }

    /**
     * 排名赛 BATCH 公布模式:当全部裁判对全部选手都已打分时,自动完成赛段一次性公布结果。
     * 判定口径:每名选手的被评裁判数 ≥ 本赛段已分配裁判数(未分配裁判时退化为至少一名裁判评过)。
     */
    private void maybeAutoPublishRankStage(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc == null || !"BATCH".equalsIgnoreCase(rc.getPublishMode())) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            return;
        }
        List<Long> refereeIds = refereeStageService.getRefereeIdsByStageId(stage.getId());
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (matches.isEmpty()) {
            return;
        }
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds)
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            return;
        }
        // 退赛选手不参与"全部打分完成"判定,否则 BATCH 模式会因退赛者从未打分而永远不自动公布
        List<Long> partIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        java.util.Set<Long> withdrawn = partIds.isEmpty() ? java.util.Set.of()
            : competitorMapper.selectByIds(partIds).stream()
                .filter(c -> OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus()))
                .map(TCompetitor::getId)
                .collect(java.util.stream.Collectors.toSet());
        parts = parts.stream()
            .filter(p -> p.getCompetitorId() == null || !withdrawn.contains(p.getCompetitorId()))
            .toList();
        if (parts.isEmpty()) {
            return;
        }
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (roundIds.isEmpty()) {
            return;
        }
        // competitorId -> 已评裁判数(去重)
        Map<Long, Set<Long>> scoredReferees = new java.util.HashMap<>();
        roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                .in(TRoundScore::getRoundId, roundIds)
                .isNotNull(TRoundScore::getRefereeId)
                .isNotNull(TRoundScore::getCompetitorId))
            .forEach(rs -> scoredReferees
                .computeIfAbsent(rs.getCompetitorId(), k -> new java.util.HashSet<>())
                .add(rs.getRefereeId()));
        int required = refereeIds.isEmpty() ? 1 : refereeIds.size();
        boolean allDone = parts.stream()
            .allMatch(p -> scoredReferees.getOrDefault(p.getCompetitorId(), Set.of()).size() >= required);
        if (!allDone) {
            return;
        }
        log.info("排名赛赛段[{}]全部裁判对全部选手打分完成,BATCH 模式自动公布", stage.getId());
        stageLifecycleService.completeStage(stage.getId());
    }

    /**
     * 海选赛加载当前累计结果(用于裁判端实时回显)。
     */
    private List<MatchScoreResult> loadAuditionResults(TMatch match, List<Long> competitorIds) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId()));

        Map<Long, java.math.BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                scores.put(p.getCompetitorId(), p.getScoreValue() != null ? p.getScoreValue() : java.math.BigDecimal.ZERO);
            }
        }
        Map<Long, Integer> ranks = RankCalculator.rank(scores);

        List<MatchScoreResult> results = new ArrayList<>();
        for (Long cid : competitorIds) {
            MatchScoreResult r = new MatchScoreResult();
            r.setCompetitorId(cid);
            r.setScoreValue(scores.getOrDefault(cid, java.math.BigDecimal.ZERO));
            r.setRankInMatch(ranks.get(cid));
            results.add(r);
        }
        return results;
    }

    private MatchResultVo buildVo(Long matchId, String status, List<MatchScoreResult> results) {
        MatchResultVo vo = new MatchResultVo();
        vo.setMatchId(matchId);
        vo.setStatus(status);
        vo.setParticipants(results.stream().map(r -> {
            ParticipantResultVo p = new ParticipantResultVo();
            p.setCompetitorId(r.getCompetitorId());
            p.setScoreValue(r.getScoreValue());
            p.setRankInMatch(r.getRankInMatch());
            p.setOutcomeStatus(r.getOutcomeStatus());
            return p;
        }).toList());
        return vo;
    }
}
