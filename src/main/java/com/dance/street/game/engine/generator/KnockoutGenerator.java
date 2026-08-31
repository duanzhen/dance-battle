package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.KnockoutConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;

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
        return generateMultiRound(seededCompetitorIds, kc);
    }

    /** 多轮模式:单赛段完整 bracket(首轮→…→决赛,跨轮连线) */
    private BracketPlan generateMultiRound(List<Long> seededCompetitorIds, KnockoutConfig kc) {
        int actualCount = seededCompetitorIds == null ? 0 : seededCompetitorIds.size();
        int bracketSize = nextPowerOfTwo(Math.max(actualCount, 2));
        int[] seeds = seedPositions(bracketSize);
        int totalRounds = log2(bracketSize);
        boolean thirdPlace = kc != null && Boolean.TRUE.equals(kc.getThirdPlaceMatch())
            && totalRounds == 2; // 季军赛仅在半决赛(4 队)场景生成

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

        // 季军赛(半决赛败者组):两场半决赛的败者互相对决,胜者为季军,在决赛(下一赛段)前举行
        if (thirdPlace) {
            for (MatchPlan mp : matches) {
                if (mp.getRound() == 1) {
                    mp.setLoserTargetRound(totalRounds + 1);
                    mp.setLoserTargetMatchIndex(0);
                    mp.setLoserTargetSlot(mp.getMatchIndex());
                }
            }
            MatchPlan third = new MatchPlan();
            third.setName("季军赛");
            third.setRound(totalRounds + 1);
            third.setMatchIndex(0);
            third.setDisplayRow(2);
            third.setDisplayCol(totalRounds + 1);
            third.setDisplayZone("CENTER");
            third.setThirdPlaceMatch(true);
            third.setFinalMatch(false);
            List<SlotPlan> slots = new ArrayList<>();
            slots.add(emptySlot(0));
            slots.add(emptySlot(1));
            third.setSlots(slots);
            matches.add(third);
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
            m.setName("第" + (i + 1) + "场");
            m.setRound(1);
            m.setMatchIndex(i);
            m.setDisplayCol(1);
            m.setDisplayRow(i);
            m.setDisplayZone(i < r1Count / 2 ? "LEFT" : "RIGHT");
            m.setFinalMatch(true);
            // 配对模式:SEQUENTIAL=1-2、3-4(相邻);SEED=标准种子摆位(SEED_LAYOUT 写死的赛事约定:
            // 16 人 = (1,16),(8,9),(12,5),(13,4),(3,14),(6,11),(10,7),(15,2),头尾交叉分散强种子)
            int seedA = seedPairing ? layout[2 * i] : (2 * i + 1);
            int seedB = seedPairing ? layout[2 * i + 1] : (2 * i + 2);
            List<SlotPlan> slots = new ArrayList<>();
            slots.add(seedSlot(0, seedA, actualCount, seededCompetitorIds));
            slots.add(seedSlot(1, seedB, actualCount, seededCompetitorIds));
            m.setSlots(slots);
            matches.add(m);
        }

        // 季军赛(半决赛败者组):4 队单轮赛段开启季军赛时,两场半决赛败者互争季军,
        // 场次与半决赛同属本赛段,胜者不晋级下一赛段(决赛在下一赛段,天然晚于季军赛)
        if (kc != null && Boolean.TRUE.equals(kc.getThirdPlaceMatch()) && r1Count == 2) {
            for (MatchPlan m : matches) {
                m.setLoserTargetRound(2);
                m.setLoserTargetMatchIndex(0);
                m.setLoserTargetSlot(m.getMatchIndex());
            }
            MatchPlan third = new MatchPlan();
            third.setName("季军赛");
            third.setRound(2);
            third.setMatchIndex(0);
            third.setDisplayRow(2);
            third.setDisplayCol(2);
            third.setDisplayZone("CENTER");
            third.setThirdPlaceMatch(true);
            third.setFinalMatch(false);
            List<SlotPlan> slots = new ArrayList<>();
            slots.add(emptySlot(0));
            slots.add(emptySlot(1));
            third.setSlots(slots);
            matches.add(third);
        }

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(bracketSize);
        plan.setMatches(matches);
        return plan;
    }

    private MatchPlan baseMatch(int round, int index, int roundCount, int totalRounds) {
        MatchPlan m = new MatchPlan();
        m.setName("第" + (index + 1) + "场");
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
     * 4 人基数(赛事约定):左侧 (1,4);右侧 (3,2)。
     * 8/16/32/64… 由 {@link #seedLayout(int)} 逐层递推生成,不查表。
     */
    static final int[] BASE_4 = {1, 4, 3, 2};

    /**
     * SEED 模式标准摆位(通用生成器,强种子分散、汇聚对称):
     * 以 4 人基数逐层翻倍,每对 (x,y)(x+y=n+1) 扩展为 {x,2n+1-x}、{y,2n+1-y}(x 的子对阵在前);
     * 新列内前半段的子对阵按 x/y 原样朝上,后半段按补数朝上;整表前一半=左列、后一半=右列。
     * 返回按 1..n 种子号排列的数组,相邻两两即首轮对阵。
     */
    public static int[] seedLayout(int n) {
        if (n <= 4) {
            return n == 4 ? BASE_4.clone() : seedPositions(n);
        }
        if ((n & (n - 1)) != 0) {
            return seedPositions(n); // 非 2 的幂:回退递归种子位
        }
        int[] prev = seedLayout(n / 2);
        int[] out = new int[n];
        int parentCount = prev.length / 2;
        int parentsPerColumn = prev.length / 4; // 每列父对阵数
        int halfChildren = parentsPerColumn;    // 每列子对阵前半段数量(父对阵数)
        int idx = 0;
        for (int i = 0; i < parentCount; i++) {
            int x = prev[2 * i];
            int y = prev[2 * i + 1];
            int cx = n + 1 - x;
            int cy = n + 1 - y;
            int childBase = (i % parentsPerColumn) * 2; // 子对阵在列内起始位置
            boolean child1AsIs = childBase < halfChildren;
            boolean child2AsIs = childBase + 1 < halfChildren;
            if (child1AsIs) {
                out[idx++] = x;
                out[idx++] = cx;
            } else {
                out[idx++] = cx;
                out[idx++] = x;
            }
            if (child2AsIs) {
                out[idx++] = y;
                out[idx++] = cy;
            } else {
                out[idx++] = cy;
                out[idx++] = y;
            }
        }
        return out;
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
