package com.dance.street.game.service.impl.settle;

/**
 * 赛段结算结果:结算是否已经"可以关闭赛段",不能关闭时的人话原因。
 *
 * <p>此前各类赛制用两种互不相同的失败语义表达同一件事(海选/排名赛
 * {@code return GAMING}、淘汰赛/小组赛直接抛异常),导播台必须知道是哪种
 * 才能正确提示。收敛成统一返回后,「赛段还不能结束」永远是一个正常结果,
 * 由调用方按 {@code reason} 提示,不再混用异常。</p>
 *
 * @param closable 结算已完成,赛段可以置为 SETTLED
 * @param reason   {@code closable=false} 时的原因(可直接展示给导播)
 * @param tiebreaker 未结束是否因为产生了同分加赛(二海/三海…):
 *                   前端据此弹「需要加赛」,而不是笼统的"仍有场次未完成"
 * @author duane
 */
public record StageSettleOutcome(boolean closable, String reason, boolean tiebreaker) {

    private static final StageSettleOutcome COMPLETED = new StageSettleOutcome(true, null, false);

    /** 结算完成,赛段可关闭 */
    public static StageSettleOutcome completed() {
        return COMPLETED;
    }

    /** 结算产生了后续工作(仍有场次未判完等),赛段保持进行中 */
    public static StageSettleOutcome pending(String reason) {
        return new StageSettleOutcome(false, reason, false);
    }

    /**
     * 结算产生了同分加赛(二海/三海…),赛段保持进行中:
     * 首次结算是当场生成加赛、再次结算是加赛还没判完,两种情况导播都要去盯加赛。
     * 单独标记出来,前端才能弹「需要加赛」而不是让人以为只是漏判。
     */
    public static StageSettleOutcome tiebreaker(String reason) {
        return new StageSettleOutcome(false, reason, true);
    }
}
