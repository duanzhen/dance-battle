package com.dance.street.game.engine.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 排名计算工具(无状态)。按 scoreValue 降序排名,同分并列(standard competition ranking: 1,2,2,4)。
 */
public final class RankCalculator {

    private RankCalculator() {
    }

    /**
     * @param scores competitorId -> 总分
     * @return competitorId -> 本场排名(1 最优);同分者并列同名次,后续名次顺延
     */
    public static Map<Long, Integer> rank(Map<Long, BigDecimal> scores) {
        Map<Long, Integer> result = new HashMap<>();
        if (scores == null || scores.isEmpty()) {
            return result;
        }
        List<Map.Entry<Long, BigDecimal>> sorted = new ArrayList<>(scores.entrySet());
        // 降序:分高者在前
        sorted.sort((a, b) -> {
            BigDecimal av = a.getValue() != null ? a.getValue() : BigDecimal.ZERO;
            BigDecimal bv = b.getValue() != null ? b.getValue() : BigDecimal.ZERO;
            return bv.compareTo(av);
        });
        int rank = 0;
        BigDecimal lastScore = null;
        for (int i = 0; i < sorted.size(); i++) {
            BigDecimal s = sorted.get(i).getValue() != null ? sorted.get(i).getValue() : BigDecimal.ZERO;
            if (lastScore == null || s.compareTo(lastScore) != 0) {
                rank = i + 1;
                lastScore = s;
            }
            result.put(sorted.get(i).getKey(), rank);
        }
        return result;
    }
}
