package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 淘汰赛对阵连线(对应 TMatch.promotion_rule JSON 中每个 slot 的去向)。
 * <pre>
 * { "0": {"action":"ADVANCE","targetMatchId":200,"targetSlot":0} }
 * </pre>
 * key = 本场 displaySlotIndex(排名位次),value = 该位次结算后的去向。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionTarget implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作:ADVANCE(填入下游场次占位)/ FINAL_ADVANCE(晋级到下一赛段)/ ELIMINATE(淘汰) */
    private String action;
    /** 去向的比赛场次ID */
    private Long targetMatchId;
    /** 填入下游场地的位置槽(displaySlotIndex) */
    private Integer targetSlot;
}
