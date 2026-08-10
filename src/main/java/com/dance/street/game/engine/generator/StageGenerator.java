package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.List;

/**
 * 赛段对阵生成策略。每种赛制一个实现,负责「纯计算」产出 {@link BracketPlan}(不落库)。
 *
 * <p>实现必须无状态、不依赖 Spring。落库由 Service 层完成。</p>
 */
public interface StageGenerator {

    /** 该生成器处理的赛制 */
    StageModeEnum mode();

    /**
     * 根据已按种子顺位排序的参赛方列表 + 赛段配置,生成对阵计划。
     *
     * @param seededCompetitorIds 按 seedRank 升序的 competitorId 列表(seeds[0] 为 1 号种子)
     * @param ruleConfig          赛段规则配置
     */
    BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig);
}
