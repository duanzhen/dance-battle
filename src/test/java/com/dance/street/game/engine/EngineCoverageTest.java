package com.dance.street.game.engine;

import com.dance.street.game.engine.generator.StageGeneratorFactory;
import com.dance.street.game.engine.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 扩展点覆盖自检:Spring 自动收集的策略是否覆盖了所有需要的枚举值。
 *
 * <p>生成器/打分策略改为 Spring 自动收集后,新增赛制或比赛模式如果漏写实现、
 * 或实现漏标 {@code @Component},要到线上「生成对阵/打分」才抛异常。这里用一个只扫描
 * {@code com.dance.street.game.engine} 的轻量上下文,把"漏实现"提前变成测试红灯——
 * 不启动数据库/Redis,秒级完成。</p>
 *
 * <p>结算策略的同类覆盖断言见 {@code StageSettlementStrategyTest#everyStageModeHasSettler}。</p>
 */
class EngineCoverageTest {

    @Test
    void everyGenerateModeHasGenerator() {
        try (AnnotationConfigApplicationContext ctx = engineContext()) {
            List<String> uncovered = ctx.getBean(StageGeneratorFactory.class).uncoveredModes();
            assertEquals(List.of(), uncovered,
                "以下赛制需要生成对阵却没有生成器实现(或漏标 @Component): " + uncovered);
        }
    }

    @Test
    void everyMatchModeHasScoreStrategy() {
        try (AnnotationConfigApplicationContext ctx = engineContext()) {
            List<String> uncovered = ctx.getBean(ScoringEngine.class).uncoveredModes();
            assertEquals(List.of(), uncovered,
                "以下比赛模式没有打分策略实现(或漏标 @Component): " + uncovered);
        }
    }

    /** 只扫描 engine 包:策略实现都是无依赖的纯计算,无需数据库/Redis。 */
    private static AnnotationConfigApplicationContext engineContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.scan("com.dance.street.game.engine");
        ctx.refresh();
        return ctx;
    }
}
