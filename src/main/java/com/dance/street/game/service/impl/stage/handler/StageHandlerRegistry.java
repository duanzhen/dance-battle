package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 赛制 Handler 注册表:Spring 收集全部 {@link StageHandler},按 stageMode 唯一映射。
 *
 * @author duane
 */
@Component
public class StageHandlerRegistry {

    private final Map<String, StageHandler> byMode;

    public StageHandlerRegistry(List<StageHandler> handlers) {
        Map<String, StageHandler> map = new LinkedHashMap<>();
        for (StageHandler handler : handlers) {
            StageHandler prev = map.put(handler.stageMode(), handler);
            if (prev != null) {
                throw new ServiceException("赛制[{}]注册了多个 StageHandler:{} 与 {}",
                    handler.stageMode(), prev.getClass().getSimpleName(),
                    handler.getClass().getSimpleName());
            }
        }
        this.byMode = Map.copyOf(map);
    }

    public StageHandler of(String stageMode) {
        StageHandler handler = byMode.get(stageMode);
        if (handler == null) {
            throw new ServiceException("赛制[{}]没有对应的 StageHandler", stageMode);
        }
        return handler;
    }

    /** 尚未注册 Handler 的赛制(启动自检 / HandlerCoverage)。 */
    public List<String> uncoveredModes() {
        return Arrays.stream(StageModeEnum.values())
            .map(StageModeEnum::getCode)
            .filter(code -> !byMode.containsKey(code))
            .collect(Collectors.toList());
    }

    public List<StageHandler> all() {
        return List.copyOf(byMode.values());
    }
}
