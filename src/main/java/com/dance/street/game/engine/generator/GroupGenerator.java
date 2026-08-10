package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 小组赛对阵生成:蛇形分组(强种子分散)+ Round-Robin 循环配对。
 *
 * <p>小组信息编码在 {@code displayZone}("G1".."Gn");无淘汰式胜者连线
 * (小组晋级由 settleStage 按组累计积分决定)。</p>
 */
public class GroupGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.GROUP;
    }

    @Override
    public BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        List<Long> seeded = seededCompetitorIds == null ? List.of() : seededCompetitorIds;
        GroupConfig gc = ruleConfig != null ? ruleConfig.getGroup() : null;
        int groupCount = (gc != null && gc.getGroupCount() != null && gc.getGroupCount() > 0)
            ? gc.getGroupCount() : 1;

        List<List<Long>> groups = snakeSplit(seeded, groupCount);

        List<MatchPlan> matches = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<Long> comps = groups.get(g);
            List<List<int[]>> rounds = roundRobinRounds(comps.size());
            for (int r = 0; r < rounds.size(); r++) {
                List<int[]> pairs = rounds.get(r);
                for (int p = 0; p < pairs.size(); p++) {
                    matches.add(buildMatch(g + 1, r + 1, p, comps.get(pairs.get(p)[0]), comps.get(pairs.get(p)[1])));
                }
            }
        }

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(seeded.size());
        plan.setMatches(matches);
        return plan;
    }

    private MatchPlan buildMatch(int groupNo, int round, int pairIndex, Long a, Long b) {
        MatchPlan m = new MatchPlan();
        m.setName("G" + groupNo + "-R" + round + "-M" + (pairIndex + 1));
        m.setRound(round);
        m.setMatchIndex(pairIndex);
        m.setDisplayCol(round);
        m.setDisplayRow(pairIndex);
        m.setDisplayZone("G" + groupNo);
        List<SlotPlan> slots = new ArrayList<>();
        slots.add(slot(0, a));
        slots.add(slot(1, b));
        m.setSlots(slots);
        // 小组赛无胜者去向连线(finalMatch=false,winnerTarget=null)
        return m;
    }

    private SlotPlan slot(int slotIndex, Long competitorId) {
        SlotPlan s = new SlotPlan();
        s.setSlotIndex(slotIndex);
        s.setCompetitorId(competitorId);
        s.setBye(false);
        return s;
    }

    /**
     * 蛇形分组:偶数行正序、奇数行逆序,使强种子均匀分散到各组。
     */
    static List<List<Long>> snakeSplit(List<Long> seeded, int groupCount) {
        List<List<Long>> groups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(new ArrayList<>());
        }
        for (int i = 0; i < seeded.size(); i++) {
            int row = i / groupCount;
            int col = i % groupCount;
            int g = (row % 2 == 0) ? col : (groupCount - 1 - col);
            groups.get(g).add(seeded.get(i));
        }
        return groups;
    }

    /**
     * Round-Robin(circle method):返回各轮的配对(0-indexed 索引)。
     * 队伍数为奇数时补一个 -1(BYE),与 BYE 的配对被跳过。
     */
    static List<List<int[]>> roundRobinRounds(int teamCount) {
        List<Integer> teams = new ArrayList<>();
        for (int i = 0; i < teamCount; i++) {
            teams.add(i);
        }
        if (teamCount % 2 != 0) {
            teams.add(-1); // BYE 占位
        }
        int m = teams.size();
        int rounds = m - 1;
        List<List<int[]>> result = new ArrayList<>();
        List<Integer> arr = new ArrayList<>(teams);
        for (int r = 0; r < rounds; r++) {
            List<int[]> roundPairs = new ArrayList<>();
            for (int i = 0; i < m / 2; i++) {
                int a = arr.get(i);
                int b = arr.get(m - 1 - i);
                if (a != -1 && b != -1) {
                    roundPairs.add(new int[]{a, b});
                }
            }
            result.add(roundPairs);
            // 固定首位,其余顺时针旋转:把末尾插到位置 1
            Integer last = arr.remove(arr.size() - 1);
            arr.add(1, last);
        }
        return result;
    }
}
