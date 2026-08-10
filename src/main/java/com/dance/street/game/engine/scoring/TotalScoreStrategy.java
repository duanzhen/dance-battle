package com.dance.street.game.engine.scoring;

import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.common.enums.MatchModeEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VOTING 模式:总分制。
 * <p>汇总每个参赛方的所有原始分(投票/计票)为 scoreValue,再排名。不设本场 WIN/LOSS,
 * 晋级由 Resolver 按排名决定。</p>
 */
public class TotalScoreStrategy implements ScoreStrategy {

    @Override
    public MatchModeEnum mode() {
        return MatchModeEnum.VOTING;
    }

    @Override
    public List<MatchScoreResult> score(MatchScoreInput input) {
        ScoringConfig cfg = input.getScoringConfig();
        AggregateRuleEnum rule = AggregateRuleEnum.fromCode(cfg != null ? cfg.getAggregateRule() : null);
        BigDecimal trimRatio = cfg != null ? cfg.getTrimRatio() : null;

        Map<Long, List<BigDecimal>> byComp = new HashMap<>();
        if (input.getRawScores() != null) {
            for (TRoundScore rs : input.getRawScores()) {
                if (rs.getCompetitorId() == null || rs.getScore() == null) {
                    continue;
                }
                byComp.computeIfAbsent(rs.getCompetitorId(), k -> new ArrayList<>()).add(rs.getScore());
            }
        }

        Map<Long, BigDecimal> scores = new HashMap<>();
        for (Long cid : input.getCompetitorIds()) {
            List<BigDecimal> vals = byComp.getOrDefault(cid, List.of());
            scores.put(cid, ScoreAggregator.aggregate(vals, rule, trimRatio));
        }

        Map<Long, Integer> ranks = RankCalculator.rank(scores);
        List<MatchScoreResult> list = new ArrayList<>();
        for (Long cid : input.getCompetitorIds()) {
            MatchScoreResult r = new MatchScoreResult();
            r.setCompetitorId(cid);
            r.setScoreValue(scores.get(cid));
            r.setRankInMatch(ranks.get(cid));
            list.add(r);
        }
        return list;
    }
}
