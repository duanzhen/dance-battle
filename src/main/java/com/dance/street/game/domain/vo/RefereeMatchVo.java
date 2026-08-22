package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 裁判端比赛数据视图
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class RefereeMatchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 裁判ID */
    private Long refereeId;

    /** 裁判姓名 */
    private String refereeName;

    /** 赛事ID */
    private Long tournamentId;

    /** 赛段信息 */
    private RefereeStageInfo stage;

    /** 裁判名下所有进行中的赛段(多赛段并行时自动/手动切换) */
    private List<RefereeStageInfo> stages;

    /** 本赛段打分配置类型(决定裁判端界面形态) */
    private String scoreType;

    /** 打分维度(RANKING 模式使用) */
    private List<RefereeDimensionInfo> dimensions;

    /** 当前比赛场次 */
    private RefereeMatchInfo match;

    /** 赛段内所有进行中的场次(多场并行时供裁判切换) */
    private List<RefereeMatchInfo> matches;

    /** 当前赛段全部场次(含待开始/已结束,顶部横向列表展示) */
    private List<RefereeMatchInfo> stageMatches;

    /** 当前赛段总览:全部场次及其轮次结果(无进行中场次时前端展示) */
    private List<RefereeStageMatchInfo> stageOverview;

    /** 当前轮次 */
    private RefereeRoundInfo currentRound;

    /** 本场所有轮次(多轮制 BO3/BO5 时用于轮次切换) */
    private List<RefereeRoundInfo> rounds;

    /** 多裁判判罚进度(已投票/应投票),单人裁判为 null */
    private String voteProgress;

    /** 结果公布模式:AUTO/MANUAL/DIRECTOR */
    private String publishMode;

    /** 公布范围:BATCH 模式使用,ALL=公布全部排名 / TOP_N=只公布前 N 名晋级名单 */
    private String publishScope;

    /** 手动公布模式:裁判已判完、等待导播台公布 */
    private Boolean pendingPublish;

    /** 参赛方列表 */
    private List<RefereeParticipantInfo> participants;

    /** 当前裁判在本轮已提交的明细(按 参赛方×维度) */
    private List<RefereeScoreInfo> myScores;

    @Data
    public static class RefereeStageInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String stageMode;
        private String status;
    }

    @Data
    public static class RefereeMatchInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String status;
        private String matchMode;
    }

    @Data
    public static class RefereeStageMatchInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String status;
        private String matchMode;
        /** 本场全部轮次(平局加赛轮 outcome=DRAW) */
        private List<RefereeRoundInfo> rounds;
        /** 本场参赛方及结果 */
        private List<RefereeStageParticipantInfo> participants;
        /** 本场胜者名称 */
        private String winnerName;
    }

    @Data
    public static class RefereeStageParticipantInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String competitorName;
        private String outcomeStatus;
    }

    @Data
    public static class RefereeRoundInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private Long roundSequence;
        private String status;
        /** 本轮判罚结果:平局加赛轮的早前轮次为 DRAW;进行中/未判定为 null */
        private String outcome;
    }

    @Data
    public static class RefereeParticipantInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String competitorName;
        private Long displaySlotIndex;
        /** 该参赛方的当前累计总分(所有裁判之和) */
        private BigDecimal currentScore;
        /** 当前裁判对该参赛方的已提交分数(用于回显) */
        private BigDecimal myScore;
        /** 该参赛方在当前累计结果中的排名 */
        private Long rankInMatch;
    }

    @Data
    public static class RefereeDimensionInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private String name;
        private java.math.BigDecimal weight;
        private java.math.BigDecimal maxScore;
    }

    @Data
    public static class RefereeScoreInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long competitorId;
        private String dimension;
        private java.math.BigDecimal score;
    }
}
