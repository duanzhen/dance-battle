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
        // 查表摆位:场0=1vs8, 场1=4vs5(左), 场2=3vs6, 场3=2vs7(右)
        assertEquals(1L, r1.get(0).getSlots().get(0).getCompetitorId());
        assertEquals(8L, r1.get(0).getSlots().get(1).getCompetitorId());
        assertEquals(4L, r1.get(1).getSlots().get(0).getCompetitorId());
        assertEquals(5L, r1.get(1).getSlots().get(1).getCompetitorId());
        assertEquals(3L, r1.get(2).getSlots().get(0).getCompetitorId());
        assertEquals(6L, r1.get(2).getSlots().get(1).getCompetitorId());
        assertEquals(2L, r1.get(3).getSlots().get(0).getCompetitorId());
        assertEquals(7L, r1.get(3).getSlots().get(1).getCompetitorId());
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
