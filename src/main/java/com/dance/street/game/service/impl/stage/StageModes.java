package com.dance.street.game.service.impl.stage;

import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 五种赛制的唯一注册表。
 */
@Component
public class StageModes {

    private final Map<StageModeEnum, StageMode> byMode;

    public StageModes(List<StageMode> modes) {
        Map<StageModeEnum, StageMode> map = new EnumMap<>(StageModeEnum.class);
        for (StageMode mode : modes) {
            StageMode prev = map.put(mode.mode(), mode);
            if (prev != null) {
                throw new ServiceException("赛制[{}]注册了多个实现:{} 与 {}",
                    mode.mode().getCode(), prev.getClass().getSimpleName(),
                    mode.getClass().getSimpleName());
            }
        }
        this.byMode = Map.copyOf(map);
    }

    public StageMode of(String stageMode) {
        StageModeEnum mode = StageModeEnum.require(stageMode);
        StageMode impl = byMode.get(mode);
        if (impl == null) {
            throw new ServiceException("赛制[{}]没有对应的实现", stageMode);
        }
        return impl;
    }

    /** 尚未注册实现的赛制。 */
    public List<String> uncoveredModes() {
        return Arrays.stream(StageModeEnum.values())
            .filter(mode -> !byMode.containsKey(mode))
            .map(StageModeEnum::getCode)
            .toList();
    }
}
