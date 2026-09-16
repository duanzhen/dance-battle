package com.dance.street.game.service.impl.settle;

import com.dance.street.game.domain.TStage;

/**
 * 赛段结算策略:一种赛制一个实现,由编排层按 {@code stageMode} 分派。
 *
 * <p>拆分前六种赛制的结算都长在 {@code TStageLifecycleServiceImpl} 里,
 * 由 {@code completeStage} 的 if-else 链选择;新增赛制要改那个类,
 * 单测也只能整体跑。拆成策略后每种赛制独立成类、可单独测,
 * 编排层只负责"选策略 → 置状态 → 广播"。</p>
 *
 * <p><b>约定:</b>策略只写参赛方结果({@code outcome_status}/{@code final_rank})
 * 与场次状态,<b>不修改赛段自身状态</b>——置 SETTLED 由编排层统一决定,
 * 避免每种赛制各写一份状态推进。</p>
 *
 * <p><b>事务边界由调用方提供:</b>实现类不加 {@code @Transactional},它们依赖
 * 编排层({@code completeStage} / {@code tryAutoSettleTiebreaker})的事务。
 * 从没有事务的上下文直接调用策略,写入将不再是原子的——新增调用点时请连事务一起考虑。</p>
 *
 * <p><b>失败语义:</b>"还不能结束"(场次未打完、有选手没打分…)一律返回
 * {@link StageSettleOutcome#pending},只有配置非法、数据损坏这类真错误才抛异常。</p>
 *
 * @author duane
 */
public interface StageSettler {

    /** 负责的赛制({@code StageModeEnum.code}) */
    String stageMode();

    /**
     * 本策略是否处理该赛制。默认只处理 {@link #stageMode()};
     * 多个赛制共用同一结算方式时(如淘汰赛与自由对抗都是"多裁判累计打分"),覆盖本方法即可。
     */
    default boolean supports(String mode) {
        return stageMode().equals(mode);
    }

    /**
     * 结算赛段。
     *
     * @return 结算结果:可关闭 / 仍需继续(附原因)
     */
    StageSettleOutcome settle(TStage stage);
}
