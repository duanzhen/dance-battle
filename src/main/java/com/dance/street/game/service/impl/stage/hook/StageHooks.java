package com.dance.street.game.service.impl.stage.hook;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 赛制行为钩子注册表:Spring 自动收集全部实现,按 stageMode 取。
 * 没有实现的赛制返回空(视为"无额外行为"),不必强迫每个赛制写空类。
 */
@Component
public class StageHooks {

    private final List<StageSetupHook> setupHooks;
    private final List<StageStartGuard> startGuards;

    public StageHooks(List<StageSetupHook> setupHooks, List<StageStartGuard> startGuards) {
        this.setupHooks = List.copyOf(setupHooks);
        this.startGuards = List.copyOf(startGuards);
    }

    public Optional<StageSetupHook> setupHook(String stageMode) {
        return setupHooks.stream().filter(h -> h.supports(stageMode)).findFirst();
    }

    public Optional<StageStartGuard> startGuard(String stageMode) {
        return startGuards.stream().filter(g -> g.supports(stageMode)).findFirst();
    }
}
