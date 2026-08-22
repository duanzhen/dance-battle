package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 排名赛排名明细:按圈返回每位参赛者的总分与各维度聚合分。
 * 供排名展示组件在「总分+维度分」显示模式下使用。
 *
 * @author duane
 */
@Data
public class RankDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 赛段ID */
    private Long stageId;

    /** 赛段名称 */
    private String stageName;

    /** 赛段状态 */
    private String status;

    /** 各圈排名明细 */
    private List<CircleRank> circles;

    @Data
    public static class CircleRank implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String zone;
        private String title;
        private List<CompetitorRank> competitors;
    }

    @Data
    public static class CompetitorRank implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String name;
        private String number;
        private Long rankInMatch;
        /** 总分(未公布时为 null) */
        private BigDecimal scoreValue;
        /** 各维度聚合分(未公布时为 null) */
        private List<DimensionScore> dimensions;
    }

    @Data
    public static class DimensionScore implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private String name;
        private BigDecimal maxScore;
        private BigDecimal score;
    }
}
