package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.StageModeProfile.GeneratePolicy;
import com.dance.street.game.engine.common.StageModeProfiles;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 赛段对阵生成器注册表(按 stageMode 路由)。
 *
 * <p>实现由 Spring 自动收集:每个 {@link StageGenerator} 实现标注 {@code @Component} 即被注册,
 * 新增赛制只加一个实现类,无须改动本类(与 {@code StageSettlerRegistry} 同一套机制)。
 * 生成器本身无状态,单测仍可直接 {@code new}。</p>
 *
 * <p>{@link #uncoveredModes()} 供测试断言:画像声明需要生成对阵
 * ({@link GeneratePolicy#GENERATE})的赛制必须有生成器实现,漏实现直接暴露。
 * ARENA(擂台赛)不生成对阵树,由导播台逐场创建,不需要生成器。</p>
 *
 * @author duane
 */
@Component
public class StageGeneratorFactory {

    private final Map<StageModeEnum, StageGenerator> generators;

    public StageGeneratorFactory(List<StageGenerator> generators) {
        Map<StageModeEnum, StageGenerator> map = new EnumMap<>(StageModeEnum.class);
        for (StageGenerator generator : generators) {
            StageGenerator previous = map.put(generator.mode(), generator);
            if (previous != null) {
                throw new ServiceException("赛制[{}]注册了多个对阵生成器:{} 与 {}",
                    generator.mode().getCode(), previous.getClass().getSimpleName(),
                    generator.getClass().getSimpleName());
            }
        }
        this.generators = Map.copyOf(map);
    }

    public BracketPlan generate(StageModeEnum mode, List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        StageGenerator generator = generators.get(mode);
        if (generator == null) {
            throw new ServiceException("暂不支持的赛制: {}", mode == null ? null : mode.getCode());
        }
        return generator.generate(seededCompetitorIds, ruleConfig);
    }

    /** 需要生成对阵(画像 {@link GeneratePolicy#GENERATE})但未注册生成器的赛制(启动自检/测试断言用)。 */
    public List<String> uncoveredModes() {
        return Arrays.stream(StageModeEnum.values())
            .filter(mode -> !generators.containsKey(mode))
            .filter(mode -> StageModeProfiles.of(mode.getCode()).setup().generatePolicy() == GeneratePolicy.GENERATE)
            .map(StageModeEnum::getCode)
            .toList();
    }
}
