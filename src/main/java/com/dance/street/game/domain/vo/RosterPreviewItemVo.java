package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 名单预览条目:一条规则候选或人工覆盖。
 *
 * @author duane
 */
@Data
public class RosterPreviewItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** SOURCE = 源赛段行;GUEST = 无源外卡 */
    private String refType;

    /** 覆盖 ID(人工条目;规则候选为 null) */
    private Long overrideId;

    /** 源赛段行(SOURCE) */
    private Long sourceCompetitorId;

    /** 来源赛段 */
    private Long sourceStageId;

    /** 入边ID(t_stage_roster_group.id):同一来源有多条平行边时区分这个人从哪条边进来 */
    private Long sourceGroupId;

    /** 来源赛段给出的原座号(备份;人工拖动后与 seedRank 可能不同) */
    private Long sourceSlot;

    /** 规则来源说明(如 "海选·落选·每圈9~24名") */
    private String groupLabel;

    /** 物化时的入场性质:ADVANCE/REVIVE/GUEST */
    private String entryTag;

    private Long playerId;

    private String name;

    private Long type;

    private String number;

    private String outcomeStatus;

    private Long finalRank;

    private BigDecimal score;

    /** 预览种子位(装配顺序,含 SEED 覆盖) */
    private Long seedRank;

    /**
     * 这一行现在能不能在中间态调整。
     *
     * <p><b>多入口汇合</b>全放开(座位本来就靠导播拖,对账不会重排已落好的行);
     * <b>单入口自动排座</b>则要来源结算后才放开——那些行会被投影按来源名次重新落座。</p>
     */
    private Boolean adjustable;

    /**
     * 这一行来自的来源赛段是不是还没结算(人还没定案)。
     *
     * <p>与 {@link #adjustable} 分开:多入口汇合里这种行<b>可以</b>先排位,但仍要告诉导播
     * "这个人后续可能变化"(来源重判/撤销结算时会从名单里消失)。单入口下它同时也是锁定的标记。</p>
     */
    private Boolean sourcePending;
}
