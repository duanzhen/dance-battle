package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.service.impl.stage.StageRunService;
import com.dance.street.game.service.impl.stage.StageSettlementService;
import com.dance.street.game.service.impl.stage.StageSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 赛段流转编排:把生命周期主路径转到 Setup / Run / Settlement。
 *
 * <p>模式专属的空钩子(initialize / generateMatches / resetToDraft)已去掉。
 * 结算与开赛后的擂台首场仍由 Setup / Run / Settlement 经 {@link StageHandlerRegistry} 分派。</p>
 *
 * @author duane
 */
@Service
@RequiredArgsConstructor
public class StageCommands {

    private final StageSetupService stageSetupService;
    private final StageRunService stageRunService;
    private final StageSettlementService stageSettlementService;

    public void initialize(InitializeStageBo bo) {
        stageSetupService.initialize(bo);
    }

    public void generateMatches(GenerateMatchesBo bo) {
        stageSetupService.generateMatches(bo);
    }

    public void startStage(Long stageId) {
        stageRunService.startStage(stageId);
    }

    public StageCompleteVo completeStage(Long stageId) {
        return stageSettlementService.completeStage(stageId);
    }

    public void resetToDraft(Long stageId) {
        stageSettlementService.resetStageToDraft(stageId);
    }
}
