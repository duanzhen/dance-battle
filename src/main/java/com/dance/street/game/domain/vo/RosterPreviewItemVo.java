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
}
