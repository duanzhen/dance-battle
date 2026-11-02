package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.impl.settle.ScoredStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 自由对抗:编排侧 GeneratePolicy=SKIP_SILENT,不经 {@link #generateBracket};
 * 结算与淘汰赛共用 {@link ScoredStageSettler}。
 */
@Component
@RequiredArgsConstructor
public class FreeMatchStageHandler implements StageHandler {

    private final ScoredStageSettler settler;

    @Override
    public String stageMode() {
        return StageModeEnum.FREE_MATCH.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }
}
