package com.dance.street.game.service.impl.settle;

import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 结算策略注册表:按 {@code stageMode} 找到对应策略。
 *
 * <p>Spring 注入全部 {@link StageSettler} 实现,新增赛制只要加一个
 * {@code @Component} 即可被 {@code completeStage} 认到,不必改编排层。</p>
 *
 * @author duane
 */
@Component
public class StageSettlerRegistry {

    private final List<StageSettler> settlers;

    public StageSettlerRegistry(List<StageSettler> settlers) {
        this.settlers = List.copyOf(settlers);
    }

    /**
     * 取该赛制的结算策略。
     *
     * @throws ServiceException 赛制没有对应策略(新增赛制时漏加实现)
     */
    public StageSettler of(String stageMode) {
        return settlers.stream()
            .filter(s -> s.supports(stageMode))
            .findFirst()
            .orElseThrow(() -> new ServiceException("赛制[{}]没有对应的结算策略,无法完成赛段", stageMode));
    }

    /** 尚未注册策略的赛制(用于启动自检/测试断言) */
    public List<String> uncoveredModes() {
        return Arrays.stream(StageModeEnum.values())
            .map(StageModeEnum::getCode)
            .filter(mode -> settlers.stream().noneMatch(s -> s.supports(mode)))
            .collect(Collectors.toList());
    }
}
