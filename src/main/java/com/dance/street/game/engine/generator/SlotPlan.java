package com.dance.street.game.engine.generator;

import lombok.Data;

/**
 * 对阵计划中一个位置槽(对应一个 TMatchParticipant)。
 */
@Data
public class SlotPlan {

    /** 位置槽序号(对应 display_slot_index) */
    private int slotIndex;

    /** 占据该槽的参赛方;null 表示待定(后续轮占位)或轮空(BYE) */
    private Long competitorId;

    /** 是否轮空(高种子对手位为空,直接晋级) */
    private boolean bye;
}
