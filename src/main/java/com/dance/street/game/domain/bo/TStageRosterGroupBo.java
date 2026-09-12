package com.dance.street.game.domain.bo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 名单来源组"取数规则":一份名单可由多组规则并集取人。
 *
 * <p>典型场景:两圈海选的复活名单 =
 * 组1(圈1·名次9~24) + 组2(圈2·名次9~24)。</p>
 *
 * @author duane
 */
@Data
public class TStageRosterGroupBo {

    /** 来源赛段(NULL=外部签到/外卡) */
    private Long sourceStageId;

    /** 结果过滤:ADVANCE/ELIMINATED/WITHDRAWN/PENDING/ANY */
    private String resultFilter;

    /** 圈/组过滤(如 ZONE-1);空 = 不限圈 */
    private String zone;

    /** 名次区间 */
    private Integer rankStart;

    private Integer rankEnd;

    /** true = 名次取"圈内名次"(participant.rank_in_match);false = 赛段全局名次(final_rank) */
    private Boolean rankByZone;

    /** 淘汰赛第几轮(display_row 过滤) */
    private Integer round;

    /** 分数区间(participant.score_value) */
    private BigDecimal scoreMin;

    private BigDecimal scoreMax;

    /** 本组填充方式:AUTO/MANUAL/STREAM */
    private String fillMode;

    /** 本组取人上限(0=不限) */
    private Integer quota;

    /** 本组优先级(小者先取且优先保留) */
    private Integer priority;

    /**
     * 组内排序键:FINAL_RANK/ZONE_RANK/ZONE_RANK_ROTATE/SCORE/NUMBER/RANDOM。
     * 空 = 自动(源赛段为多圈海选时按圈内名次轮转,否则按全局名次)。
     */
    private String orderBy;
}
