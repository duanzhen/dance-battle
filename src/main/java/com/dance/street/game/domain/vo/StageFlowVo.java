package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 大屏赛程流转视图:赛事全部赛段链 + 当前进行中的赛段/场次
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class StageFlowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 按流程顺序排列的赛段(排除 DISCARD) */
    private List<StageFlowItem> stages;

    /** 当前进行中赛段ID(无则为 null) */
    private Long currentStageId;

    /** 当前进行中的场次(当前赛段第一场 GAMING) */
    private StageFlowMatch currentMatch;

    /** 当前场次参赛方 */
    private List<StageFlowParticipant> currentMatchParticipants;

    @Data
    public static class StageFlowItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String stageMode;
        private String status;
        private Long teamCountStart;
        private Long teamCountEnd;
        private Long isInitialized;
    }

    @Data
    public static class StageFlowMatch implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String status;
        private String matchMode;
    }

    @Data
    public static class StageFlowParticipant implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String competitorName;
        /** 参赛方首位成员的选手照片 */
        private String avatar;
        private Long displaySlotIndex;
        private BigDecimal scoreValue;
        private Long rankInMatch;
        private String outcomeStatus;
    }
}
