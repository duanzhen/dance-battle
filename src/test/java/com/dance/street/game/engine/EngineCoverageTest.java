package com.dance.street.game.engine;

import com.dance.street.game.engine.generator.AuditionGenerator;
import com.dance.street.game.engine.generator.KnockoutGenerator;
import com.dance.street.game.engine.generator.RankGenerator;
import com.dance.street.game.engine.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 扩展点覆盖自检:需要建树的三种赛制都有生成器,每种比赛模式都有打分策略。
 *
 * <p>只扫描 {@code com.dance.street.game.engine},不启动数据库/Redis。</p>
 */
class EngineCoverageTest {

    @Test
    void everyGenerateModeHasGenerator() {
        try (AnnotationConfigApplicationContext ctx = engineContext()) {
            assertNotNull(ctx.getBean(AuditionGenerator.class));
            assertNotNull(ctx.getBean(RankGenerator.class));
            assertNotNull(ctx.getBean(KnockoutGenerator.class));
            assertEquals(3, ctx.getBeansOfType(AuditionGenerator.class).size()
                + ctx.getBeansOfType(RankGenerator.class).size()
                + ctx.getBeansOfType(KnockoutGenerator.class).size());
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
