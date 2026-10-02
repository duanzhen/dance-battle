package com.dance.street.game.service.impl.stage.hook;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;

/**
 * 生成对阵前的赛制专属<b>行为</b>钩子(需要算法/查询的差异)。
 * 只有真正有差异的赛制才实现;取值型差异请放 {@code StageModeProfile}。
 */
public interface StageSetupHook {

    String stageMode();

    default boolean supports(String mode) {
        return stageMode().equals(mode);
    }

    /** 生成对阵前的赛制专属校验(配置非法抛 ServiceException)。 */
    default void validateBeforeGenerate(TStage stage, RuleConfigHolder ruleConfig) {
    }
}
