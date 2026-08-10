package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.ScoringEngine;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多裁判累计打分场次服务实现。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TScoredMatchServiceImpl implements ITScoredMatchService {

    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ScoringEngine scoringEngine = new ScoringEngine();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MatchScoreResult> accumulateScores(TMatch match, TStage stage, SubmitResultBo bo) {
        TMatchRound round = mustGetRound(match);
        Long refId = bo.getRefereeId() != null ? bo.getRefereeId() : 0L;
        // 重复提交以最新为准:先删该裁判本轮旧分
        roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaUpdate()
            .eq(TRoundScore::getRoundId, round.getId())
            .eq(TRoundScore::getRefereeId, refId));

        if (bo.getScores() != null && !bo.getScores().isEmpty()) {
            for (var se : bo.getScores()) {
                TRoundScore rs = new TRoundScore();
                rs.setTournamentId(match.getTournamentId());
                rs.setRoundId(round.getId());
                // 裁判端无登录租户上下文,需显式带租户,否则 tenant_id 插入报错
                rs.setTenantId(match.getTenantId());
                rs.setCompetitorId(se.getCompetitorId());
                rs.setRefereeId(se.getRefereeId() != null ? se.getRefereeId() : refId);
                rs.setScore(se.getScore());
                rs.setDimension(StringUtils.isNotBlank(se.getDimension()) ? se.getDimension() : StageConstants.DIMENSION_MAIN);
                rs.setAction(StringUtils.isNotBlank(se.getAction()) ? se.getAction() : StageConstants.SCORE_ACTION_SCORE);
                roundScoreMapper.insert(rs);
            }
        }

        // 用全部裁判分重算总分/排名并回写 participant(场次保持 GAMING)
        List<MatchScoreResult> results = computeAll(match, stage);
        writeParticipantScores(match, results, null);
        refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "scores");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "scores");
        log.info("场次[{}]裁判[{}]累计打分完成,当前共{}条明细", match.getId(), refId,
            roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery().eq(TRoundScore::getRoundId, round.getId())));
        return results;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleScoredMatches(Long stageId) {
        List<TMatch> pending = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
        if (pending.isEmpty()) {
            return;
        }
        TStage stage = stageMapper.selectById(stageId);
        for (TMatch match : pending) {
            MatchModeEnum mode = MatchModeEnum.fromCode(match.getMatchMode());
            if (mode != MatchModeEnum.VOTING && mode != MatchModeEnum.RANKING) {
                continue;
            }
            List<MatchScoreResult> results = computeAll(match, stage);
            // 按赛制做后处理
            if (StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
                resolveKnockoutDownstream(match, results);
            } else if (StageModeEnum.GROUP.getCode().equals(stage.getStageMode())) {
                writeGroupOutcomes(match, results);
            }
            writeParticipantScores(match, results, null);
            TMatch mUpd = new TMatch();
            mUpd.setId(match.getId());
            mUpd.setStatus(StageConstants.MATCH_SETTLED);
            matchMapper.updateById(mUpd);
            refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "match");
            tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "match");
            log.info("多裁判场次[{}]已结算:{}人参与,模式={}", match.getId(), results.size(), mode.getCode());
        }
    }

    /** 用全部裁判分计算本场最终总分/排名 */
    private List<MatchScoreResult> computeAll(TMatch match, TStage stage) {
        TMatchRound round = mustGetRound(match);
        List<TRoundScore> all = roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().eq(TRoundScore::getRoundId, round.getId()));
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId()));
        List<Long> competitorIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.fromCode(match.getMatchMode()))
            .scoringConfig(rc != null ? rc.getScoring() : null)
            .competitorIds(competitorIds)
            .rawScores(all)
            .build();
        return scoringEngine.compute(input);
    }

    /** 回写 participant 的 score_value / rank_in_match / outcomeStatus(null 表示不改) */
    private void writeParticipantScores(TMatch match, List<MatchScoreResult> results, String outcomeStatus) {
        for (MatchScoreResult r : results) {
            TMatchParticipant upd = new TMatchParticipant();
            upd.setScoreValue(r.getScoreValue());
            upd.setRankInMatch(r.getRankInMatch() == null ? null : r.getRankInMatch().longValue());
            if (outcomeStatus != null) {
                upd.setOutcomeStatus(outcomeStatus);
            }
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, r.getCompetitorId()));
        }
    }

    /** 淘汰赛:败者标淘汰,胜者填下游占位或标晋级下一赛段 */
    private void resolveKnockoutDownstream(TMatch match, List<MatchScoreResult> results) {
        for (MatchScoreResult r : results) {
            if (r.getCompetitorId() == null) {
                continue;
            }
            if (r.getRankInMatch() == null || r.getRankInMatch() > 1) {
                markCompetitorOutcome(r.getCompetitorId(), OutcomeStatusEnum.ELIMINATED.getCode());
            }
        }
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
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setCompetitorId(winner.getCompetitorId());
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, winnerTarget.getTargetMatchId())
                .eq(TMatchParticipant::getDisplaySlotIndex, winnerTarget.getTargetSlot().longValue()));
        }
    }

    /** 小组赛:按本场排名写胜负(同分并列第一记平),供小组积分结算使用 */
    private void writeGroupOutcomes(TMatch match, List<MatchScoreResult> results) {
        if (results.size() != 2) {
            log.warn("小组赛多裁判场次[{}]参赛方数量={},跳过胜负回写", match.getId(), results.size());
            return;
        }
        MatchScoreResult a = results.get(0);
        MatchScoreResult b = results.get(1);
        boolean tie = a.getRankInMatch() != null && a.getRankInMatch().equals(b.getRankInMatch());
        String aOut = tie ? MatchOutcomeEnum.DRAW.getCode()
            : (a.getRankInMatch() != null && a.getRankInMatch() == 1 ? MatchOutcomeEnum.WIN.getCode() : MatchOutcomeEnum.LOSS.getCode());
        String bOut = tie ? MatchOutcomeEnum.DRAW.getCode()
            : (b.getRankInMatch() != null && b.getRankInMatch() == 1 ? MatchOutcomeEnum.WIN.getCode() : MatchOutcomeEnum.LOSS.getCode());
        updateParticipantOutcome(match.getId(), a.getCompetitorId(), aOut);
        updateParticipantOutcome(match.getId(), b.getCompetitorId(), bOut);
    }

    private void updateParticipantOutcome(Long matchId, Long competitorId, String outcome) {
        TMatchParticipant upd = new TMatchParticipant();
        upd.setOutcomeStatus(outcome);
        participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, matchId)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
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
            return rounds.get(0);
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
}
