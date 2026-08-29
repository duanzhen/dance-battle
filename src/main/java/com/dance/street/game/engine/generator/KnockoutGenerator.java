package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.KnockoutConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 淘汰赛对阵生成。
 *
 * <p>核心:标准种子位排布(蛇形镜像 [1,N,2,N-1,...] 避免强强过早相遇)、
 * 队伍数非 2 的幂时高种子对手位填 BYE 轮空、后续轮占位 + 胜者去向连线。</p>
 */
public class KnockoutGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.KNOCKOUT;
    }

    @Override
    public BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        KnockoutConfig kc = ruleConfig != null ? ruleConfig.getKnockout() : null;
        int round = ruleConfig != null && ruleConfig.getKnockoutRound() != null
            ? ruleConfig.getKnockoutRound() : 1;
        if (kc != null && Boolean.TRUE.equals(kc.getSingleRound())) {
            return generateSingleRound(seededCompetitorIds, kc, round);
        }
        return generateMultiRound(seededCompetitorIds);
    }

    /** 多轮模式:单赛段完整 bracket(首轮→…→决赛,跨轮连线) */
    private BracketPlan generateMultiRound(List<Long> seededCompetitorIds) {
        int actualCount = seededCompetitorIds == null ? 0 : seededCompetitorIds.size();
        int bracketSize = nextPowerOfTwo(Math.max(actualCount, 2));
        int[] seeds = seedPositions(bracketSize);
        int totalRounds = log2(bracketSize);

        List<MatchPlan> matches = new ArrayList<>();

        // 第 1 轮:按种子位填充参赛方或 BYE
        int r1Count = bracketSize / 2;
        for (int i = 0; i < r1Count; i++) {
            MatchPlan m = baseMatch(1, i, r1Count, totalRounds);
            List<SlotPlan> slots = new ArrayList<>();
            slots.add(seedSlot(0, seeds[2 * i], actualCount, seededCompetitorIds));
            slots.add(seedSlot(1, seeds[2 * i + 1], actualCount, seededCompetitorIds));
            m.setSlots(slots);
            matches.add(m);
        }

        // 第 2..N 轮:全部占位(competitorId 待定,等上游胜者填入)
        for (int r = 2; r <= totalRounds; r++) {
            int count = bracketSize / (1 << r);
            for (int i = 0; i < count; i++) {
                MatchPlan m = baseMatch(r, i, count, totalRounds);
                List<SlotPlan> slots = new ArrayList<>();
                slots.add(emptySlot(0));
                slots.add(emptySlot(1));
                m.setSlots(slots);
                matches.add(m);
            }
        }

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(bracketSize);
        plan.setMatches(matches);
        return plan;
    }

    /**
     * 单轮模式(每轮一赛段):只生成首轮 N/2 场,每场胜者全部晋级下一赛段(finalMatch)。
     * 用于 32-16、16-8 等独立赛段,赛段间靠晋级流转(AdvancementService)串联。
     * displayZone 前半 LEFT、后半 RIGHT,便于 widget 两列布局。
     */
    private BracketPlan generateSingleRound(List<Long> seededCompetitorIds, KnockoutConfig kc, int round) {
        int actualCount = seededCompetitorIds == null ? 0 : seededCompetitorIds.size();
        int bracketSize = nextPowerOfTwo(Math.max(actualCount, 2));
        int r1Count = bracketSize / 2;
        boolean seedPairing = kc != null && "SEED".equalsIgnoreCase(kc.getPairingMode());
        // SEED=标准 bracket 摆位(8/16/32 查表);其余人数递归生成
        int[] layout = seedPairing ? seedLayout(bracketSize) : null;

        List<MatchPlan> matches = new ArrayList<>();
        for (int i = 0; i < r1Count; i++) {
            MatchPlan m = new MatchPlan();
            m.setName("第" + (i + 1) + "场Round" + round);
            m.setRound(1);
            m.setMatchIndex(i);
            m.setDisplayCol(1);
            m.setDisplayRow(i);
            m.setDisplayZone(i < r1Count / 2 ? "LEFT" : "RIGHT");
            m.setFinalMatch(true);
            // 配对模式:SEQUENTIAL=1-2、3-4(相邻);SEED=标准种子摆位(SEED_LAYOUT 写死的赛事约定:
            // 16 人 = (1,16),(8,9),(5,12),(4,13),(3,14),(6,11),(7,10),(2,15),头尾交叉分散强种子)
            int seedA = seedPairing ? layout[2 * i] : (2 * i + 1);
            int seedB = seedPairing ? layout[2 * i + 1] : (2 * i + 2);
            // SEED 摆位:同一场两个种子上下位置随机,保持配对不变
            if (seedPairing && ThreadLocalRandom.current().nextBoolean()) {
                int tmp = seedA;
                seedA = seedB;
                seedB = tmp;
            }
            List<SlotPlan> slots = new ArrayList<>();
            slots.add(seedSlot(0, seedA, actualCount, seededCompetitorIds));
            slots.add(seedSlot(1, seedB, actualCount, seededCompetitorIds));
            m.setSlots(slots);
            matches.add(m);
        }

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(bracketSize);
        plan.setMatches(matches);
        return plan;
    }

    private MatchPlan baseMatch(int round, int index, int roundCount, int totalRounds) {
        MatchPlan m = new MatchPlan();
        m.setName("第" + (index + 1) + "场Round" + round);
        m.setRound(round);
        m.setMatchIndex(index);
        m.setDisplayCol(round);
        m.setDisplayRow(index);
        m.setDisplayZone(zoneOf(index, roundCount, totalRounds, round));
        setDownstream(m, round, index, totalRounds);
        return m;
    }

    private SlotPlan seedSlot(int slotIndex, int seed, int actualCount, List<Long> seeded) {
        SlotPlan s = new SlotPlan();
        s.setSlotIndex(slotIndex);
        // 槽位无参赛方(人数不足补位 或 种子位空缺)一律视为轮空,统一由轮空自动结算处理
        Long cid = seed <= actualCount ? seeded.get(seed - 1) : null;
        s.setCompetitorId(cid);
        s.setBye(cid == null);
        return s;
    }

    private SlotPlan emptySlot(int slotIndex) {
        SlotPlan s = new SlotPlan();
        s.setSlotIndex(slotIndex);
        s.setCompetitorId(null);
        s.setBye(false);
        return s;
    }

    private void setDownstream(MatchPlan m, int round, int index, int totalRounds) {
        if (round >= totalRounds) {
            m.setFinalMatch(true); // 胜者晋级下一赛段
        } else {
            m.setWinnerTargetRound(round + 1);
            m.setWinnerTargetMatchIndex(index / 2);
            m.setWinnerTargetSlot(index % 2);
        }
    }

    private String zoneOf(int index, int roundCount, int totalRounds, int round) {
        if (round == totalRounds) {
            return "CENTER";
        }
        return index < roundCount / 2 ? "LEFT" : "RIGHT";
    }

    /**
     * SEED 模式标准 bracket 摆位(种子号顺序,每两个一对,前半 LEFT 后半 RIGHT)。
     * 8/16/32 为赛事约定摆位(强种子分散、汇聚对称);其余人数回退 seedPositions。
     */
    static final Map<Integer, int[]> SEED_LAYOUT = Map.of(
        8,  new int[]{1, 8, 4, 5, 3, 6, 2, 7},
        16, new int[]{1, 16, 8, 9, 5, 12, 4, 13, 3, 14, 6, 11, 7, 10, 2, 15},
        32, new int[]{1, 32, 9, 24, 16, 17, 8, 25, 5, 28, 13, 20, 12, 21, 4, 29,
                      3, 30, 11, 22, 14, 19, 6, 27, 7, 26, 15, 18, 10, 23, 2, 31}
    );

    /**
     * SEED 模式标准摆位:8/16/32 用赛事约定查表,其余人数按递归种子位生成。
     * 返回按 1..n 种子号排列的数组,相邻两两即首轮对阵。
     */
    public static int[] seedLayout(int n) {
        int[] table = SEED_LAYOUT.get(n);
        return table != null ? table : seedPositions(n);
    }

    /**
     * 标准种子位序列(n 为 2 的幂)。n=8 → [1,8,4,5,2,7,3,6],
     * 相邻两两配对即首轮对阵:(1,8),(4,5),(2,7),(3,6)。
     */
    static int[] seedPositions(int n) {
        if (n == 1) {
            return new int[]{1};
        }
        int[] prev = seedPositions(n / 2);
        int[] res = new int[n];
        for (int i = 0; i < prev.length; i++) {
            res[2 * i] = prev[i];
            res[2 * i + 1] = n + 1 - prev[i];
        }
        return res;
    }

    static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    static int log2(int v) {
        int r = 0;
        while ((1 << r) < v) {
            r++;
        }
        return r;
    }
}
