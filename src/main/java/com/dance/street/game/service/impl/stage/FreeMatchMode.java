package com.dance.street.game.service.impl.stage;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.impl.settle.ScoredStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 自由对抗:场次由导播手动添加。结束条件与淘汰赛相同。 */
@Component
@RequiredArgsConstructor
public class FreeMatchMode implements StageMode {

    private final ScoredStageSettler settler;

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.FREE_MATCH;
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }
}
