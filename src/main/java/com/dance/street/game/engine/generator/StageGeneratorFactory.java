package com.dance.street.game.engine.generator;

import org.dromara.common.core.exception.ServiceException;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.List;
import java.util.Map;

/**
 * 赛段对阵生成器工厂(按 stageMode 路由)。无状态,不依赖 Spring。
 *
 * <p>已注册 KNOCKOUT/GROUP/AUDITION/RANK/FREE_MATCH 五种;ARENA(擂台赛)不在此列——
 * 擂台赛不生成对阵树,开始赛段后由导播台按轮转队列逐场创建对决。</p>
 *
 * @author duane
 */
public class StageGeneratorFactory {

    private final Map<StageModeEnum, StageGenerator> generators;

    public StageGeneratorFactory() {
        this.generators = Map.of(
            StageModeEnum.KNOCKOUT, new KnockoutGenerator(),
            StageModeEnum.GROUP, new GroupGenerator(),
            StageModeEnum.AUDITION, new AuditionGenerator(),
            StageModeEnum.RANK, new RankGenerator(),
            StageModeEnum.FREE_MATCH, new FreeMatchGenerator()
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
