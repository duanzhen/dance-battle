package com.dance.street.game.engine.generator;

import lombok.Data;

import java.util.List;

/**
 * 对阵计划中的一场比赛(对应一个 TMatch,生成阶段纯计算,不含真实 matchId)。
 *
 * <p>下游引用用「轮次 + 轮内序号 + 槽位」表达(winnerTargetRound/MatchIndex/Slot),
 * 由 Service 落库后回填为真实 matchId 写入 promotion_rule。</p>
 */
@Data
public class MatchPlan {

    /** 场次名,如 第1场 */
    private String name;

    /** 轮次(1=首轮) */
    private int round;

    /** 轮内序号 */
    private int matchIndex;

    /** 画布 Y 轴 */
    private Integer displayRow;

    /** 画布 X 轴(= 轮次) */
    private Integer displayCol;

    /** 显示区域 LEFT/RIGHT/CENTER */
    private String displayZone;

    /** 位置槽(每方) */
    private List<SlotPlan> slots;

    /** 胜者去向:下游轮次(null 表示决赛,胜者晋级下一赛段) */
    private Integer winnerTargetRound;

    /** 胜者去向:下游轮内序号 */
    private Integer winnerTargetMatchIndex;

    /** 胜者去向:填入下游场地的槽位 */
    private Integer winnerTargetSlot;

    /** 败者去向:下游轮次(季军赛等败者组场次;null 表示无败者路由) */
    private Integer loserTargetRound;

    /** 败者去向:下游轮内序号 */
    private Integer loserTargetMatchIndex;

    /** 败者去向:填入下游场地的槽位 */
    private Integer loserTargetSlot;

    /** 是否决赛(胜者最终晋级到下一赛段) */
    private boolean finalMatch;

    /** 是否季军赛(半决赛开启季军赛时生成的败者组场次,胜者为季军) */
    private boolean thirdPlaceMatch;
}
