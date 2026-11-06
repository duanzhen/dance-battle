package com.dance.street.game.service.impl.stage;

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

/** 排名赛:单场承载全员,完成赛段时按总分排名。 */
@Component
@RequiredArgsConstructor
public class RankMode implements StageMode {

    private final RankGenerator generator;
    private final RankStageSettler settler;

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.RANK;
    }

    @Override
    public BracketPlan plan(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        return generator.generate(seededCompetitorIds, ruleConfig);
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }
}
