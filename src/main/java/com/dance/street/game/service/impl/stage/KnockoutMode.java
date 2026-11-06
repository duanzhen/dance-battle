package com.dance.street.game.service.impl.stage;

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

/** 淘汰赛:一轮一个赛段。结算与自由对抗共用同一套「场次都结束了吗」。 */
@Component
@RequiredArgsConstructor
public class KnockoutMode implements StageMode {

    private final KnockoutGenerator generator;
    private final ScoredStageSettler settler;

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.KNOCKOUT;
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
