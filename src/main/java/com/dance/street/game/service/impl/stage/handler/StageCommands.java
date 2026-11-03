package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.stage.StageRunService;
import com.dance.street.game.service.impl.stage.StageSettlementService;
import com.dance.street.game.service.impl.stage.StageSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 赛段流转编排:状态翻转与横切在此(或委托 Setup/Run/Settle),模式内部只经 {@link StageHandlerRegistry}。
 *
 * <p>本类不 import 任何具体 *StageHandler 实现。Phase 3a 下 initialize/generate/start/complete/reset
 * 仍复用现有 Setup/Run/Settlement 实现,但 settle/afterStart/capabilities 已走 Handler。</p>
 *
 * @author duane
 */
@Service
@RequiredArgsConstructor
public class StageCommands {

    private final StageHandlerRegistry handlers;
    private final StageLookup stageLookup;
    private final StageSetupService stageSetupService;
    private final StageRunService stageRunService;
    private final StageSettlementService stageSettlementService;

    public StageHandler handlerOf(String stageMode) {
        return handlers.of(stageMode);
    }

    public StageHandler handlerOfStage(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        return handlers.of(stage.getStageMode());
    }

    public void initialize(InitializeStageBo bo) {
        stageSetupService.initialize(bo);
        handlerOfStage(bo.getStageId()).initialize(bo);
    }

    public void generateMatches(GenerateMatchesBo bo) {
        stageSetupService.generateMatches(bo);
        handlerOfStage(bo.getStageId()).generateMatches(bo);
    }

    public void startStage(Long stageId) {
        stageRunService.startStage(stageId);
    }

    public StageCompleteVo completeStage(Long stageId) {
        return stageSettlementService.completeStage(stageId);
    }

    public void resetToDraft(Long stageId) {
        stageSettlementService.resetStageToDraft(stageId);
        handlerOfStage(stageId).resetToDraft(stageId);
    }
}
