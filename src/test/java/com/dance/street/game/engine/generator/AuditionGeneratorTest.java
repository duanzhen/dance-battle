package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选对阵生成单测:只建圈结构(空圈),不分配选手。
 *
 * <p>落圈由客户端在签到时指定,生成器不得按号码或随机把人塞进圈——
 * 否则同一批号码会因调用顺序不同得到不同的分圈结果。</p>
 */
@Tag("local")
class AuditionGeneratorTest {

    private final AuditionGenerator generator = new AuditionGenerator();

    private RuleConfigHolder config(Integer circles, Boolean randomSplit) {
        RuleConfigHolder rc = new RuleConfigHolder();
        rc.setCircles(circles);
        rc.setRandomSplit(randomSplit);
        return rc;
    }

    @Test
    void singleCircle_isSingleCenterMatchWithoutSlots() {
        BracketPlan plan = generator.generate(List.of(11L, 22L), config(1, null));

        assertEquals(1, plan.getMatches().size());
        MatchPlan m = plan.getMatches().get(0);
        assertEquals("海选赛", m.getName());
        assertEquals("CENTER", m.getDisplayZone());
        assertEquals(1, m.getRound());
        assertTrue(m.isFinalMatch(), "每圈胜者晋级下一赛段");
        assertNotNull(m.getSlots());
        assertEquals(0, m.getSlots().size(), "单圈也不预置选手,落圈由签到写入");
    }

    @Test
    void multiCircle_buildsEmptyCirclesInOrder() {
        BracketPlan plan = generator.generate(List.of(), config(3, null));

        assertEquals(3, plan.getMatches().size());
        for (int c = 0; c < 3; c++) {
            MatchPlan m = plan.getMatches().get(c);
            assertEquals("海选赛-" + (c + 1) + "圈", m.getName());
            assertEquals("ZONE-" + (c + 1), m.getDisplayZone());
            assertEquals(c, m.getMatchIndex());
            assertEquals(c, m.getDisplayRow(), "圈序号决定展示行");
            assertEquals(0, m.getSlots().size(), "预建圈应为空圈");
        }
    }

    /** 关键回归:传入种子选手也必须被忽略,生成结果只能是空圈。 */
    @Test
    void seededCompetitorsAreNeverDistributed() {
        List<Long> seeds = IntStream.rangeClosed(1, 5).mapToObj(Long::valueOf).toList();

        BracketPlan plan = generator.generate(seeds, config(2, null));

        assertEquals(2, plan.getMatches().size());
        int totalSlots = plan.getMatches().stream().mapToInt(m -> m.getSlots().size()).sum();
        assertEquals(0, totalSlots, "生成器不得把选手分配到圈里(落圈权归客户端)");
        assertEquals(0, plan.getBracketSize(), "生成器不再持有参赛方,不计对阵规模");
    }

    /** randomSplit 只影响前端展示模式;生成器一律建空圈,结果与分圈方式无关。 */
    @Test
    void randomSplitDoesNotChangeGeneratedStructure() {
        List<Long> seeds = IntStream.rangeClosed(1, 4).mapToObj(Long::valueOf).toList();

        BracketPlan ordered = generator.generate(seeds, config(2, false));
        BracketPlan random = generator.generate(seeds, config(2, true));

        assertEquals(
            ordered.getMatches().stream().map(MatchPlan::getDisplayZone).toList(),
            random.getMatches().stream().map(MatchPlan::getDisplayZone).toList());
        assertEquals(0, random.getMatches().stream().mapToInt(m -> m.getSlots().size()).sum());
    }

    @Test
    void invalidCircleCountFallsBackToSingleCircle() {
        assertEquals(1, generator.generate(List.of(), config(0, null)).getMatches().size());
        assertEquals(1, generator.generate(List.of(), config(null, null)).getMatches().size());
        assertEquals(1, generator.generate(List.of(), null).getMatches().size());
    }
}
