package com.dance.street.game.service.impl.stage;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.impl.settle.ArenaStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 擂台:不预生成对阵树。开赛后的第一场由 {@link StageRunService} 直接创建。 */
@Component
@RequiredArgsConstructor
public class ArenaMode implements StageMode {

    private final ArenaStageSettler settler;

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.ARENA;
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }
}
