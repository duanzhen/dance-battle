package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 下一赛段对战树预排视图:上一赛段已产生(含未最终确认)的胜者按种子顺位排入下一赛段
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class PreBracketVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;
    private String stageName;
    private String stageMode;

    /**
     * NO_PREV=无上一赛段; UNSUPPORTED=仅淘汰赛之间支持预排; WAIT_PREV=上一赛段尚未产生胜者;
     * PREVIEW=预排中; GENERATED=本赛段已生成参赛方(直接按真实种子返回)
     */
    private String status;

    /** 按预排种子顺位排列的参赛方(1..n) */
    private List<PreSeed> seededCompetitors;

    /** KNOCKOUT 预排配对(LEFT/RIGHT 两列) */
    private List<PrePair> pairs;

    @Data
    public static class PreSeed implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String name;
        private Long seedRank;
        /** 来源参赛方(上一赛段) */
        private Long sourceCompetitorId;
        /** 来源场次名(淘汰赛胜者),如 第1场 */
        private String sourceMatchName;
    }

    @Data
    public static class PrePair implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 1-based 对阵序号 */
        private Integer position;
        private String zone;
        private PreSeed left;
        private PreSeed right;
        /** 左位状态:WINNER=已有胜者 / TBD=对应场次未打完(待定) / BYE=无对应场次(轮空) */
        private String leftStatus;
        /** 右位状态:WINNER / TBD / BYE */
        private String rightStatus;
    }
}
