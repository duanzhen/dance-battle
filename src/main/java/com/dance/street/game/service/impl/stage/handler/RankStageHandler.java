package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.RankGenerator;
import com.dance.street.game.service.impl.settle.RankStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 排名赛:生成委托 {@link RankGenerator};结算委托 {@link RankStageSettler}。 */
@Component
@RequiredArgsConstructor
public class RankStageHandler implements StageHandler {

    private final RankGenerator generator;
    private final RankStageSettler settler;

    @Override
    public String stageMode() {
        return StageModeEnum.RANK.getCode();
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
