package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 排名赛结果统一视图:官方总分/名次 + 各维度聚合分 + 各裁判原始分。
 * 名单页、结果排序与 Excel 原始数据导出共用该口径。
 *
 * @author duane
 */
@Data
public class RankResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;
    private String stageName;
    private String status;
    private String aggregateRule;
    private String refereeAggregateRule;
    private BigDecimal trimRatio;
    private List<DimensionInfo> dimensions;
    private List<CompetitorItem> competitors;

    @Data
    public static class DimensionInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private String name;
        private BigDecimal weight;
        private BigDecimal maxScore;
    }

    @Data
    public static class CompetitorItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String number;
        private String name;
        private Long matchId;
        private String zone;
        /** 官方总分(按全部裁判、全部维度及配置规则计算;未打分为 null) */
        private BigDecimal score;
        /** 本场名次(并列同名次;未打分为 null) */
        private Long rankInMatch;
        /** 赛段最终名次(结算后写入) */
        private Long finalRank;
        /** ADVANCE / ELIMINATED / PENDING / WITHDRAWN */
        private String outcomeStatus;
        private List<DimensionScore> dimensions;
    }

    @Data
    public static class DimensionScore implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private String name;
        private BigDecimal weight;
        private BigDecimal maxScore;
        private BigDecimal score;
        /** 该维度下各裁判的原始分 */
        private List<RefereeScoreItem> refereeScores;
    }

    @Data
    public static class RefereeScoreItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long refereeId;
        private String refereeName;
        private BigDecimal score;
    }
}
