package com.dance.street.game.service.impl.stage;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import org.dromara.common.core.exception.ServiceException;

import java.util.List;

/**
 * 一种赛制。编排按 {@link StageModeEnum} 找到实现,只问「怎么建树」和「怎么结算」。
 * 不修改 {@code t_stage.status}。
 */
public interface StageMode {

    StageModeEnum mode();

    /**
     * 纯计算产出对阵树。不生成对阵的赛制走默认实现,编排应先看 {@link StageModeEnum#generatesBracket()}。
     */
    default BracketPlan plan(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        throw new ServiceException("赛制[{}]不生成对阵树", mode().getCode());
    }

    /** 写参赛方结果,不改赛段状态。 */
    StageSettleOutcome settle(TStage stage);
}
