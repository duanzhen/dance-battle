package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 小组赛对阵生成单测:蛇形分组、Round-Robin 配对、场次结构。
 */
@Tag("local")
class GroupGeneratorTest {

    private final GroupGenerator generator = new GroupGenerator();

    @Test
    void snakeSplit_distributesSeeds() {
        // 8 人 2 组:G0=[1,4,5,8], G1=[2,3,6,7]
        List<List<Long>> groups = GroupGenerator.snakeSplit(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), 2);
        assertEquals(2, groups.size());
        assertEquals(List.of(1L, 4L, 5L, 8L), groups.get(0));
        assertEquals(List.of(2L, 3L, 6L, 7L), groups.get(1));
    }

    @Test
    void roundRobin_fourTeams_singleCycle() {
        // 4 队单循环:3 轮 × 2 场 = 6 场(即 C(4,2))
        List<List<int[]>> rounds = GroupGenerator.roundRobinRounds(4);
        assertEquals(3, rounds.size());
        int total = rounds.stream().mapToInt(List::size).sum();
        assertEquals(6, total);
        // 每队应恰好出现 3 次(与另外 3 队各赛一场)
    }

    @Test
    void roundRobin_oddTeams_hasByeRound() {
        // 3 队:补 1 个 BYE → 4 槽 3 轮,每轮 1 场实战(另 1 场 BYE 被跳过)
        List<List<int[]>> rounds = GroupGenerator.roundRobinRounds(3);
        assertEquals(3, rounds.size());
        int total = rounds.stream().mapToInt(List::size).sum();
        assertEquals(3, total); // C(3,2)=3
    }

    @Test
    void eightPlayers_twoGroups_matchStructure() {
        List<Long> seeds = List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L);
        RuleConfigHolder rc = new RuleConfigHolder();
        GroupConfig gc = new GroupConfig();
        gc.setGroupCount(2);
        rc.setGroup(gc);
        BracketPlan plan = generator.generate(seeds, rc);

        assertEquals(8, plan.getBracketSize());
        // 2 组 × 每组 6 场 = 12 场
        assertEquals(12, plan.getMatches().size());

        long g1 = plan.getMatches().stream().filter(m -> "G1".equals(m.getDisplayZone())).count();
        long g2 = plan.getMatches().stream().filter(m -> "G2".equals(m.getDisplayZone())).count();
        assertEquals(6, g1);
        assertEquals(6, g2);

        // 每场两方均非空(无占位/BYE),且无胜者连线
        MatchPlan first = plan.getMatches().get(0);
        assertNotNull(first.getSlots().get(0).getCompetitorId());
        assertNotNull(first.getSlots().get(1).getCompetitorId());
        assertFalse(first.isFinalMatch());
        assertNull(first.getWinnerTargetRound());
    }
}
