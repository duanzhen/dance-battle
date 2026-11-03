package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.AuditionGenerator;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.service.impl.settle.AuditionStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 海选:生成委托 {@link AuditionGenerator};结算委托 {@link AuditionStageSettler}。 */
@Component
@RequiredArgsConstructor
public class AuditionStageHandler implements StageHandler {

    private final AuditionGenerator generator;
    private final AuditionStageSettler settler;

    @Override
    public String stageMode() {
        return StageModeEnum.AUDITION.getCode();
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
