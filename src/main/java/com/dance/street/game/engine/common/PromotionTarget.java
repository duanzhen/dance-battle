package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 淘汰赛对阵连线(对应 TMatch.promotion_rule JSON 中每个去向)。
 *
 * <pre>
 * {
 *   "1": {"action":"ADVANCE","targetMatchId":200,"targetSlot":0},
 *   "2": {"action":"ADVANCE","targetMatchId":201,"targetSlot":0}
 * }
 * </pre>
 *
 * <p>key 是<b>语义槽位</b>而不是 displaySlotIndex:{@code "1"} = 本场第 1 名(胜者),
 * {@code "2"} = 本场第 2 名(败者,仅季军赛这类败者组场次有)。生成侧见
 * {@code TStageLifecycleServiceImpl#generateMatchesInternal} 的回填循环,
 * 消费侧见 {@code DownstreamRouter#route}。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionTarget implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作:ADVANCE(填入下游场次占位)/ FINAL_ADVANCE(晋级到下一赛段) */
    private String action;
    /** 去向的比赛场次ID */
    private Long targetMatchId;
    /** 填入下游场地的位置槽(displaySlotIndex) */
    private Integer targetSlot;
}
