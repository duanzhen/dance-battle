package com.dance.street.game.service.impl;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.MatchRoundScoreVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TMatchParticipantVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.engine.common.ScorePublishState;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 大屏公开接口的成绩遮罩:公布前不把真实分数/判罚传给前端,
 * 但保留「该裁判已打/已判」标记,供评分详情与当前场次矩阵显示勾。
 *
 * <p>只用于 {@code /tournament/screen/**};导播台与裁判端走原接口,仍看真实结果。</p>
 */
@RequiredArgsConstructor
@Component
public class ScreenResultMasker {

    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;

    /** 遮罩单场公开详情;空入参直接返回 */
    public void maskMatch(TMatchVo vo) {
        if (vo != null) {
            maskMatches(List.of(vo));
        }
    }

    /**
     * 当前场次控件({@code /tournament/screen/flow})的公开遮罩:
     * 公布前去掉参赛方总分/名次,以及未结算场次上的胜负结果。
     */
    public void maskFlow(StageFlowVo vo) {
        if (vo == null || vo.getCurrentMatch() == null) {
            return;
        }
        TMatch match = vo.getCurrentMatch().getId() == null ? null
            : matchMapper.selectById(vo.getCurrentMatch().getId());
        TStage stage = match == null || match.getStageId() == null ? null
            : stageMapper.selectById(match.getStageId());
        if (vo.getCurrentMatchParticipants() == null || vo.getCurrentMatchParticipants().isEmpty()) {
            return;
        }
        if (ScorePublishState.hideRankPublic(stage, match)) {
            for (StageFlowVo.StageFlowParticipant p : vo.getCurrentMatchParticipants()) {
                p.setScoreValue(null);
                p.setRankInMatch(null);
            }
        }
        if (ScorePublishState.hideJudgementPublic(stage, vo.getCurrentMatch().getStatus())) {
            for (StageFlowVo.StageFlowParticipant p : vo.getCurrentMatchParticipants()) {
                p.setOutcomeStatus(null);
            }
        }
    }

    /** 遮罩场次列表。始终去掉 resultJson,避免待公布的胜负/标记泄漏。 */
    public void maskMatches(List<TMatchVo> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        Map<Long, TStage> stageById = loadStages(matches.stream()
            .map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList());
        Map<Long, TMatch> entityById = loadMatches(matches.stream()
            .map(TMatchVo::getId).filter(Objects::nonNull).distinct().toList());
        for (TMatchVo vo : matches) {
            // 待公布 JSON 对观众无意义,且可能带出胜负
            vo.setResultJson(null);
            TStage stage = vo.getStageId() == null ? null : stageById.get(vo.getStageId());
            TMatch entity = vo.getId() == null ? null : entityById.get(vo.getId());
            if (ScorePublishState.hideRankPublic(stage, entity)) {
                maskRankRoundScores(vo);
            }
            if (ScorePublishState.hideJudgementPublic(stage, vo.getStatus())) {
                maskJudgements(vo);
            }
        }
    }

    /**
     * 参赛方列表:未公布的排名赛清空总分/名次。
     * 仅供大屏 participant 接口调用;管理端走原服务时若已自行遮罩则保持兼容。
     */
    public void maskParticipants(List<TMatchParticipantVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> matchIds = list.stream()
            .map(TMatchParticipantVo::getMatchId).filter(Objects::nonNull).distinct().toList();
        if (matchIds.isEmpty()) {
            return;
        }
        Map<Long, TMatch> matchById = loadMatches(matchIds);
        Map<Long, TStage> stageById = loadStages(matchById.values().stream()
            .map(TMatch::getStageId).filter(Objects::nonNull).distinct().toList());
        for (TMatchParticipantVo vo : list) {
            TMatch match = vo.getMatchId() == null ? null : matchById.get(vo.getMatchId());
            TStage stage = match == null || match.getStageId() == null ? null : stageById.get(match.getStageId());
            if (ScorePublishState.hideRankPublic(stage, match)) {
                vo.setScoreValue(null);
                vo.setRankInMatch(null);
            }
        }
    }

    private void maskRankRoundScores(TMatchVo vo) {
        if (vo.getRoundScores() == null) {
            return;
        }
        for (MatchRoundScoreVo row : vo.getRoundScores()) {
            row.setScore(null);
            maskRefereeScores(row.getRefereeScores());
            if (row.getDimensions() == null) {
                continue;
            }
            for (MatchRoundScoreVo.DimensionScore dim : row.getDimensions()) {
                dim.setScore(null);
                maskRefereeScores(dim.getRefereeScores());
            }
        }
    }

    private void maskRefereeScores(List<MatchRoundScoreVo.RefereeScore> refs) {
        if (refs == null) {
            return;
        }
        for (MatchRoundScoreVo.RefereeScore ref : refs) {
            if (ref.getScore() != null || Boolean.TRUE.equals(ref.getScored())) {
                ref.setScored(true);
            }
            ref.setScore(null);
        }
    }

    private void maskJudgements(TMatchVo vo) {
        vo.setLeftWin(null);
        vo.setRightWin(null);
        vo.setWinnerName(null);
        vo.setLeftVotes(null);
        vo.setRightVotes(null);
        vo.setDrawVotes(null);
        vo.setSeriesLeftWins(null);
        vo.setSeriesRightWins(null);
        vo.setSeriesWinnerSide(null);
        maskVoteList(vo.getRefereeVotes());
        if (vo.getRoundVotes() == null) {
            return;
        }
        for (TMatchVo.RoundVoteInfo round : vo.getRoundVotes()) {
            round.setWinnerSide(null);
            round.setOutcome(null);
            maskVoteList(round.getRefereeVotes());
        }
    }

    private void maskVoteList(List<TMatchVo.RefereeVoteInfo> votes) {
        if (votes == null) {
            return;
        }
        for (TMatchVo.RefereeVoteInfo v : votes) {
            boolean judged = v.getVote() != null || Boolean.TRUE.equals(v.getVoted());
            v.setVoted(judged ? Boolean.TRUE : Boolean.FALSE);
            v.setVote(null);
        }
    }

    private Map<Long, TStage> loadStages(Collection<Long> stageIds) {
        if (stageIds == null || stageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TStage> map = new HashMap<>();
        for (TStage s : stageMapper.selectByIds(stageIds)) {
            map.put(s.getId(), s);
        }
        return map;
    }

    private Map<Long, TMatch> loadMatches(Collection<Long> matchIds) {
        if (matchIds == null || matchIds.isEmpty()) {
            return Map.of();
        }
        return matchMapper.selectByIds(matchIds).stream()
            .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
    }
}
