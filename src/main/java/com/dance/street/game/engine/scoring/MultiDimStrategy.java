package com.dance.street.game.engine.scoring;

import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.common.enums.MatchModeEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RANKING 模式:多维度多裁判。两步聚合:
 * <ol>
 *   <li>每个 (competitor, dimension) 用 {@code refereeAggregateRule} 合并多裁判分 → 单维度分</li>
 *   <li>各维度分用 {@code aggregateRule} 合成总分;WEIGHTED 时按维度 {@code weight} 加权</li>
 * </ol>
 */
public class MultiDimStrategy implements ScoreStrategy {

    @Override
    public MatchModeEnum mode() {
        return MatchModeEnum.RANKING;
    }

    @Override
    public List<MatchScoreResult> score(MatchScoreInput input) {
        ScoringConfig cfg = input.getScoringConfig();
        AggregateRuleEnum refRule = AggregateRuleEnum.fromCode(cfg != null ? cfg.getRefereeAggregateRule() : null);
        AggregateRuleEnum dimRule = AggregateRuleEnum.fromCode(cfg != null ? cfg.getAggregateRule() : null);
        BigDecimal trimRatio = cfg != null ? cfg.getTrimRatio() : null;
        List<DimensionConfig> dims = cfg != null ? cfg.getDimensions() : null;

        // 按 (competitor, dimension) 分组裁判分
        Map<Long, Map<String, List<BigDecimal>>> byCompDim = new HashMap<>();
        if (input.getRawScores() != null) {
            for (TRoundScore rs : input.getRawScores()) {
                if (rs.getCompetitorId() == null || rs.getScore() == null) {
                    continue;
                }
                String dim = rs.getDimension() != null ? rs.getDimension() : StageConstants.DIMENSION_MAIN;
                byCompDim
                    .computeIfAbsent(rs.getCompetitorId(), k -> new HashMap<>())
                    .computeIfAbsent(dim, k -> new ArrayList<>())
                    .add(rs.getScore());
            }
        }

        Map<Long, BigDecimal> scores = new HashMap<>();
        for (Long cid : input.getCompetitorIds()) {
            Map<String, List<BigDecimal>> byDim = byCompDim.getOrDefault(cid, Map.of());

            // 每个维度先用 refRule 合并多裁判分 → 维度分(LinkedHashMap 保序以便 WEIGHTED 对齐)
            Map<String, BigDecimal> dimScores = new LinkedHashMap<>();
            for (Map.Entry<String, List<BigDecimal>> e : byDim.entrySet()) {
                dimScores.put(e.getKey(), ScoreAggregator.aggregate(e.getValue(), refRule, trimRatio));
            }

            BigDecimal total;
            if (dimRule == AggregateRuleEnum.WEIGHTED && dims != null && !dims.isEmpty()) {
                // 按配置维度顺序对齐维度分与权重
                List<BigDecimal> values = new ArrayList<>();
                List<BigDecimal> weights = new ArrayList<>();
                for (DimensionConfig d : dims) {
                    values.add(dimScores.getOrDefault(d.getKey(), BigDecimal.ZERO));
                    weights.add(d.getWeight());
                }
                total = ScoreAggregator.weightedSum(values, weights);
            } else {
                total = ScoreAggregator.aggregate(new ArrayList<>(dimScores.values()), dimRule, trimRatio);
            }
            scores.put(cid, total);
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
