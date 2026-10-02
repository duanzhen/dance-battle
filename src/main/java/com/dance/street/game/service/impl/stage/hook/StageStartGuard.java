package com.dance.street.game.service.impl.stage.hook;

import com.dance.street.game.domain.TStage;

/**
 * 生成/初始化完成之后、置 GAMING 之前的赛制专属<b>行为</b>守卫。
 * 通用守卫(状态、赛段链、名单就绪)由编排层负责,这里只放"某某赛制才需要"的校验。
 */
public interface StageStartGuard {

    String stageMode();

    default boolean supports(String mode) {
        return stageMode().equals(mode);
    }

    /** 不满足即抛 ServiceException。 */
    default void assertPostGenerate(TStage stage) {
    }
}
