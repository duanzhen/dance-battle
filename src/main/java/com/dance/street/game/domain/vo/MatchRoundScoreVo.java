package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 海选场次轮次评分视图(每轮对应一名选手)。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class MatchRoundScoreVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 轮次ID */
    private Long roundId;

    /** 轮次序号(上场顺序,从 1 开始) */
    private Long roundSequence;

    /** 本轮选手ID */
    private Long competitorId;

    /** 本轮选手名称 */
    private String competitorName;

    /** 本轮选手的参赛号码(号牌):导播台按号喊人/对号时用 */
    private String competitorNumber;

    /** 本轮累计总分(所有裁判合计;未打分时为 null) */
    private BigDecimal score;

    /** 本轮选手结果(结算后):ADVANCE=晋级 / ELIMINATED=淘汰 / PENDING=待定 */
    private String outcomeStatus;

    /** 各裁判打分明细 */
    private List<RefereeScore> refereeScores;

    /**
     * 排名赛各评分维度的聚合分(按维度配置顺序);海选为空。
     * 供导播台/大屏按「每维度一列」展示。
     */
    private List<DimensionScore> dimensions;

    @Data
    public static class DimensionScore implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 维度标识(如 TECH/CREATE/SHOW) */
        private String key;

        /** 维度名称 */
        private String name;

        /** 该维度聚合分(多裁判按裁判间汇总规则合并;无人打分时为 null) */
        private BigDecimal score;

        /** 该维度下各裁判的原始分(多裁判时导播台可看到每个裁判的单项分) */
        private List<RefereeScore> refereeScores;
    }

    @Data
    public static class RefereeScore implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 裁判ID */
        private Long refereeId;

        /** 裁判名称 */
        private String refereeName;

        /** 该裁判本轮打分 */
        private BigDecimal score;
    }
}
