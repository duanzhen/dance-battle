package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.impl.settle.ArenaStageSettler;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import com.dance.street.game.service.impl.stage.ArenaRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 擂台:不生成对阵树(REJECT);结算委托 Settler;afterStart 开首场。 */
@Component
@RequiredArgsConstructor
public class ArenaStageHandler implements StageHandler {

    private final ArenaStageSettler settler;
    private final ArenaRunService arenaRunService;

    @Override
    public String stageMode() {
        return StageModeEnum.ARENA.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        return settler.settle(stage);
    }

    @Override
    public void afterStart(Long stageId) {
        arenaRunService.startNextArenaMatch(stageId);
    }
}
