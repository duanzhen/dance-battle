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

    /** 本轮累计总分(所有裁判合计;未打分时为 null) */
    private BigDecimal score;

    /** 各裁判打分明细 */
    private List<RefereeScore> refereeScores;

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
