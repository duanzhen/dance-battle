package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.KnockoutGenerator;
import com.dance.street.game.service.impl.settle.ScoredStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 淘汰赛:生成委托 {@link KnockoutGenerator};结算委托 {@link ScoredStageSettler}。 */
@Component
@RequiredArgsConstructor
public class KnockoutStageHandler implements StageHandler {

    private final KnockoutGenerator generator;
    private final ScoredStageSettler settler;

    @Override
    public String stageMode() {
        return StageModeEnum.KNOCKOUT.getCode();
    }

    @Override
    public BracketPlan generateBracket(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        return generator.generate(seededCompetitorIds, ruleConfig);
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }
}
