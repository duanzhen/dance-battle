package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.KnockoutConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 淘汰赛对阵生成算法单测:种子位排布、对阵配对、轮空 BYE、占位、决赛与下游连线。
 */
@Tag("local")
class KnockoutGeneratorTest {

    private final KnockoutGenerator generator = new KnockoutGenerator();

    @Test
    void seedPositions_standard8() {
        // n=8 → [1,8,4,5,2,7,3,6]
        assertArrayEquals(new int[]{1, 8, 4, 5, 2, 7, 3, 6}, KnockoutGenerator.seedPositions(8));
        assertArrayEquals(new int[]{1, 4, 2, 3}, KnockoutGenerator.seedPositions(4));
        assertArrayEquals(new int[]{1, 2}, KnockoutGenerator.seedPositions(2));
    }

    @Test
    void eightPlayers_bracketStructure() {
        List<Long> seeds = List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L);
        BracketPlan plan = generator.generate(seeds, null);

        assertEquals(8, plan.getBracketSize());
        // 4 + 2 + 1 = 7 场
        assertEquals(7, plan.getMatches().size());

        List<MatchPlan> r1 = round(plan, 1);
        assertEquals(4, r1.size());

        // 首场:1 号种子 vs 8 号种子
        MatchPlan m0 = r1.get(0);
        assertEquals(101L, m0.getSlots().get(0).getCompetitorId());
        assertEquals(108L, m0.getSlots().get(1).getCompetitorId());

        // 首轮均无 BYE
        assertEquals(0, r1.stream().mapToInt(this::byeCount).sum());

        // 第 2 轮:占位,competitorId 待定
        List<MatchPlan> r2 = round(plan, 2);
        assertEquals(2, r2.size());
        assertNull(r2.get(0).getSlots().get(0).getCompetitorId());
        assertNull(r2.get(0).getSlots().get(1).getCompetitorId());

        // 决赛:finalMatch=true,无下游
        List<MatchPlan> r3 = round(plan, 3);
        assertEquals(1, r3.size());
        assertTrue(r3.get(0).isFinalMatch());
        assertNull(r3.get(0).getWinnerTargetRound());
    }

    @Test
    void eightPlayers_downstreamWiring() {
        List<Long> seeds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        BracketPlan plan = generator.generate(seeds, null);

        // R1-M0 胜者 → R2-M0 slot0;R1-M1 胜者 → R2-M0 slot1;R1-M2 → R2-M1 slot0;R1-M3 → R2-M1 slot1
        List<MatchPlan> r1 = round(plan, 1);
        assertDownstream(r1.get(0), 2, 0, 0);
        assertDownstream(r1.get(1), 2, 0, 1);
        assertDownstream(r1.get(2), 2, 1, 0);
        assertDownstream(r1.get(3), 2, 1, 1);

        // R2 胜者 → 决赛(R3)
        List<MatchPlan> r2 = round(plan, 2);
        assertDownstream(r2.get(0), 3, 0, 0);
        assertDownstream(r2.get(1), 3, 0, 1);
    }

    @Test
    void fivePlayers_producesByes() {
        // 5 人 → bracketSize=8,8 个种子位中 6/7/8 为 BYE(共 3 个)
        List<Long> seeds = List.of(1L, 2L, 3L, 4L, 5L);
        BracketPlan plan = generator.generate(seeds, null);

        assertEquals(8, plan.getBracketSize());
        List<MatchPlan> r1 = round(plan, 1);
        // seeds=[1,8,4,5,2,7,3,6] → BYE 在 seed 6,7,8 → 3 个 BYE 槽
        int byes = r1.stream().mapToInt(this::byeCount).sum();
        assertEquals(3, byes);

        // 1 号种子(seed=1)对手为 seed=8 → BYE
        MatchPlan m0 = r1.get(0);
        assertEquals(1L, m0.getSlots().get(0).getCompetitorId());
        assertTrue(m0.getSlots().get(1).isBye());
    }

    @Test
    void singleRound_onlyFirstRound_allFinalMatch() {
        // 单轮模式:8 人只生成首轮 4 场,每场 finalMatch(胜者全晋级下一赛段)
        RuleConfigHolder rc = new RuleConfigHolder();
        KnockoutConfig kc = new KnockoutConfig();
        kc.setSingleRound(true);
        rc.setKnockout(kc);

        List<Long> seeds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        BracketPlan plan = generator.generate(seeds, rc);

        assertEquals(8, plan.getBracketSize());
        assertEquals(4, plan.getMatches().size(), "单轮应只有 4 场");
        // 全部为 round 1,无后续轮
        assertTrue(plan.getMatches().stream().allMatch(m -> m.getRound() == 1));
        // 每场都是 finalMatch(胜者晋级下一赛段),无下游连线
        assertTrue(plan.getMatches().stream().allMatch(MatchPlan::isFinalMatch));
        assertTrue(plan.getMatches().stream().allMatch(m -> m.getWinnerTargetRound() == null));
        // displayZone 前半 LEFT 后半 RIGHT(两列)
        long left = plan.getMatches().stream().filter(m -> "LEFT".equals(m.getDisplayZone())).count();
        long right = plan.getMatches().stream().filter(m -> "RIGHT".equals(m.getDisplayZone())).count();
        assertEquals(2, left);
        assertEquals(2, right);
    }

    @Test
    void singleRound_seedPairing_topVsBottom() {
        // SEED 配对:8 人标准 bracket 种子位 → (1,8)(4,5)(2,7)(3,6),强种子分散
        RuleConfigHolder rc = new RuleConfigHolder();
        KnockoutConfig kc = new KnockoutConfig();
        kc.setSingleRound(true);
        kc.setPairingMode("SEED");
        rc.setKnockout(kc);

        List<Long> seeds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        BracketPlan plan = generator.generate(seeds, rc);
        List<MatchPlan> r1 = round(plan, 1);

        assertEquals(4, r1.size());
        // 8 人基数摆位:场0=(1,8)、场1=(5,4)、场2=(3,6)、场3=(7,2),强种子分散
        List<List<Long>> expectedPairs = List.of(
            List.of(1L, 8L), List.of(5L, 4L), List.of(3L, 6L), List.of(7L, 2L));
        for (int i = 0; i < expectedPairs.size(); i++) {
            List<Long> pair = expectedPairs.get(i);
            List<Long> actual = List.of(
                r1.get(i).getSlots().get(0).getCompetitorId(),
                r1.get(i).getSlots().get(1).getCompetitorId());
            assertTrue(actual.containsAll(pair) && pair.containsAll(actual),
                "场" + i + " 应为配对 " + pair + ",实际 " + actual);
        }
        // 上下位置固定(不再随机):slot0 始终是摆位表左值,slot1 是右值
        // 8 人摆位 [1,8,5,4,3,6,7,2] → 场0=(1,8)、场1=(5,4)、场2=(3,6)、场3=(7,2)
        int[] layout = KnockoutGenerator.seedLayout(8);
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(Long.valueOf(layout[2 * i]), r1.get(i).getSlots().get(0).getCompetitorId(),
                "场" + i + " 上方应为种子 " + layout[2 * i]);
            assertEquals(Long.valueOf(layout[2 * i + 1]), r1.get(i).getSlots().get(1).getCompetitorId(),
                "场" + i + " 下方应为种子 " + layout[2 * i + 1]);
        }
    }

    @Test
    void fourPlayers_singleRound_thirdPlaceMatch_generatesLoserMatch() {
        // 4 人单轮半决赛(每轮一赛段)开启季军赛:两场半决赛 + 季军赛(3 场),败者路由到季军赛
        RuleConfigHolder rc = new RuleConfigHolder();
        KnockoutConfig kc = new KnockoutConfig();
        kc.setSingleRound(true);
        kc.setThirdPlaceMatch(true);
        rc.setKnockout(kc);

        List<Long> seeds = List.of(1L, 2L, 3L, 4L);
        BracketPlan plan = generator.generate(seeds, rc);

        assertEquals(4, plan.getBracketSize());
        assertEquals(3, plan.getMatches().size(), "4 人半决赛开启季军赛应生成 3 场(2 半决赛 + 1 季军赛)");

        List<MatchPlan> r1 = round(plan, 1);
        assertEquals(2, r1.size());
        // 两场半决赛:胜者晋级下一赛段(finalMatch),败者进季军赛(slot 对应场次序号)
        for (int i = 0; i < 2; i++) {
            assertTrue(r1.get(i).isFinalMatch());
            assertEquals(2, r1.get(i).getLoserTargetRound());
            assertEquals(0, r1.get(i).getLoserTargetMatchIndex());
            assertEquals(i, r1.get(i).getLoserTargetSlot());
        }

        List<MatchPlan> r2 = round(plan, 2);
        assertEquals(1, r2.size());
        MatchPlan third = r2.get(0);
        assertTrue(third.isThirdPlaceMatch(), "季军赛场次应标记 thirdPlaceMatch");
        assertEquals("季军赛", third.getName());
        assertFalse(third.isFinalMatch(), "季军赛胜者不晋级下一赛段");
        assertNull(third.getWinnerTargetRound());
        assertEquals("CENTER", third.getDisplayZone());
    }

    @Test
    void fourPlayers_multiRound_thirdPlaceMatch_generatesLoserMatch() {
        // 4 人完整单赛段 bracket 开启季军赛:2 半决赛 + 决赛 + 季军赛(4 场),半决赛败者路由到季军赛(R3)
        RuleConfigHolder rc = new RuleConfigHolder();
        KnockoutConfig kc = new KnockoutConfig();
        kc.setThirdPlaceMatch(true);
        rc.setKnockout(kc);

        List<Long> seeds = List.of(1L, 2L, 3L, 4L);
        BracketPlan plan = generator.generate(seeds, rc);

        assertEquals(4, plan.getMatches().size(), "4 人完整 bracket 开启季军赛应生成 4 场(2 半决赛 + 决赛 + 季军赛)");

        List<MatchPlan> r1 = round(plan, 1);
        for (int i = 0; i < 2; i++) {
            assertDownstream(r1.get(i), 2, 0, i);
            assertEquals(3, r1.get(i).getLoserTargetRound());
            assertEquals(0, r1.get(i).getLoserTargetMatchIndex());
            assertEquals(i, r1.get(i).getLoserTargetSlot());
        }

        List<MatchPlan> r3 = round(plan, 3);
        assertEquals(1, r3.size());
        assertTrue(r3.get(0).isThirdPlaceMatch());
        assertEquals("季军赛", r3.get(0).getName());
        assertFalse(r3.get(0).isFinalMatch());
    }

    @Test
    void fourPlayers_thirdPlaceDisabled_noExtraMatch() {
        // 4 人半决赛未开启季军赛:仅 2 场半决赛 + 决赛占位
        List<Long> seeds = List.of(1L, 2L, 3L, 4L);
        BracketPlan plan = generator.generate(seeds, null);

        assertEquals(3, plan.getMatches().size(), "4 人标准 bracket 应为 2 半决赛 + 1 决赛");
        assertTrue(plan.getMatches().stream().noneMatch(MatchPlan::isThirdPlaceMatch));
        assertTrue(plan.getMatches().stream().allMatch(m -> m.getLoserTargetRound() == null));
    }

    @Test
    void sixtyFour_seedLayout_matchesAuditionInterleave() {
        // 64 人海选后淘汰赛摆位:通用生成器从 8 人基数递推,左侧 16 场 + 右侧 16 场
        int[] layout = KnockoutGenerator.seedLayout(64);
        assertEquals(64, layout.length);

        int[][] left = {
            {1, 64}, {32, 33}, {16, 49}, {17, 48}, {8, 57}, {25, 40}, {9, 56}, {24, 41},
            {44, 21}, {53, 12}, {37, 28}, {60, 5}, {45, 20}, {52, 13}, {36, 29}, {61, 4}
        };
        int[][] right = {
            {3, 62}, {30, 35}, {14, 51}, {19, 46}, {6, 59}, {27, 38}, {11, 54}, {22, 43},
            {42, 23}, {55, 10}, {39, 26}, {58, 7}, {47, 18}, {50, 15}, {34, 31}, {63, 2}
        };
        for (int i = 0; i < 16; i++) {
            assertArrayEquals(left[i], new int[]{layout[2 * i], layout[2 * i + 1]}, "左半区场" + (i + 1));
            assertArrayEquals(right[i], new int[]{layout[32 + 2 * i], layout[32 + 2 * i + 1]}, "右半区场" + (i + 1));
        }
        // 全部 64 个种子恰好各出现一次
        boolean[] seen = new boolean[65];
        for (int v : layout) {
            assertTrue(v >= 1 && v <= 64, "种子越界: " + v);
            assertFalse(seen[v], "种子重复: " + v);
            seen[v] = true;
        }
    }

    @Test
    void seedLayout_recursionMatchesAllLevels() {
        // 4 人基数:左侧 (1,4);右侧 (3,2)
        assertArrayEquals(new int[]{1, 4, 3, 2}, KnockoutGenerator.seedLayout(4));
        // 8 人基数
        assertArrayEquals(new int[]{1, 8, 5, 4, 3, 6, 7, 2}, KnockoutGenerator.seedLayout(8));
        // 16 人:左侧 (1,16),(8,9),(12,5),(13,4);右侧 (3,14),(6,11),(10,7),(15,2)
        assertArrayEquals(
            new int[]{1, 16, 8, 9, 12, 5, 13, 4, 3, 14, 6, 11, 10, 7, 15, 2},
            KnockoutGenerator.seedLayout(16));
        // 32 人:左侧 (1,32),(16,17),(8,25),(9,24),(21,12),(28,5),(20,13),(29,4);
        //        右侧 (3,30),(14,19),(6,27),(11,22),(23,10),(26,7),(18,15),(31,2)
        assertArrayEquals(
            new int[]{1, 32, 16, 17, 8, 25, 9, 24, 21, 12, 28, 5, 20, 13, 29, 4,
                      3, 30, 14, 19, 6, 27, 11, 22, 23, 10, 26, 7, 18, 15, 31, 2},
            KnockoutGenerator.seedLayout(32));
    }

    private List<MatchPlan> round(BracketPlan plan, int round) {
        return plan.getMatches().stream()
            .filter(m -> m.getRound() == round)
            .sorted((a, b) -> Integer.compare(a.getMatchIndex(), b.getMatchIndex()))
            .collect(Collectors.toList());
    }

    private int byeCount(MatchPlan m) {
        return (int) m.getSlots().stream().filter(SlotPlan::isBye).count();
    }

    private void assertDownstream(MatchPlan m, int targetRound, int targetIndex, int targetSlot) {
        assertEquals(targetRound, m.getWinnerTargetRound(), "winnerTargetRound of " + m.getName());
        assertEquals(targetIndex, m.getWinnerTargetMatchIndex(), "winnerTargetMatchIndex of " + m.getName());
        assertEquals(targetSlot, m.getWinnerTargetSlot(), "winnerTargetSlot of " + m.getName());
    }
}
