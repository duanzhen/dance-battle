package com.dance.street.game.engine.generator;

import org.dromara.common.core.exception.ServiceException;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.List;
import java.util.Map;

/**
 * 赛段对阵生成器工厂(按 stageMode 路由)。无状态,不依赖 Spring。
 *
 * <p>MVP 仅注册 KNOCKOUT;GROUP 在 M4 加入;其余赛制后续增量扩展。</p>
 */
public class StageGeneratorFactory {

    private final Map<StageModeEnum, StageGenerator> generators;

    public StageGeneratorFactory() {
        this.generators = Map.of(
            StageModeEnum.KNOCKOUT, new KnockoutGenerator(),
            StageModeEnum.GROUP, new GroupGenerator(),
            StageModeEnum.AUDITION, new AuditionGenerator(),
            StageModeEnum.RANK, new RankGenerator()
        );
    }

    public BracketPlan generate(StageModeEnum mode, List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        StageGenerator generator = generators.get(mode);
        if (generator == null) {
            throw new ServiceException("暂不支持的赛制: {}", mode == null ? null : mode.getCode());
        }
        return generator.generate(seededCompetitorIds, ruleConfig);
    }
}
