package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 排名赛对阵生成:与海选赛同构——所有参赛方进入同一场次(可分圈),
 * 每个参赛方一个轮次,由裁判逐选手按多个自定义维度打分,结算时按总分排名晋级。
 *
 * <p>排名赛本身不产生两两对决,因此只需一场(或每圈一场)承载全部选手,
 * 后续由裁判端逐选手提交维度分,最终由 completeStage 聚合排名并晋级。</p>
 */
public class RankGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.RANK;
    }

    @Override
    public BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        List<Long> seeded = seededCompetitorIds == null ? List.of() : seededCompetitorIds;
        int circles = 1;
        if (ruleConfig != null && ruleConfig.getCircles() != null) {
            circles = Math.max(1, ruleConfig.getCircles());
        }
        if (circles > seeded.size()) {
            circles = Math.max(1, seeded.size());
        }
        // 随机分圈:先打乱再均分,选手随机分到各圈场次
        if (ruleConfig != null && Boolean.TRUE.equals(ruleConfig.getRandomSplit())) {
            List<Long> shuffled = new ArrayList<>(seeded);
            Collections.shuffle(shuffled);
            seeded = shuffled;
        }
        List<MatchPlan> matches = new ArrayList<>();

        // 分圈:每圈一个场次,每圈人数尽量均分(余数从前圈开始多分1人)
        int base = seeded.size() / circles;
        int remainder = seeded.size() % circles;
        int cursor = 0;
        for (int c = 0; c < circles; c++) {
            int count = base + (c < remainder ? 1 : 0);
            MatchPlan m = new MatchPlan();
            if (circles == 1) {
                m.setName("排名赛");
                m.setDisplayZone("CENTER");
            } else {
                m.setName("排名赛-" + (c + 1) + "圈");
                m.setDisplayZone("ZONE-" + (c + 1));
            }
            m.setRound(1);
            m.setMatchIndex(c);
            m.setDisplayCol(1);
            m.setDisplayRow(c);
            m.setFinalMatch(true); // 每圈胜者(按名额)晋级下一赛段

            List<SlotPlan> slots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                SlotPlan s = new SlotPlan();
                s.setSlotIndex(i + 1); // 上场顺序从1开始,与选手号对齐
                s.setCompetitorId(seeded.get(cursor++));
                s.setBye(false);
                slots.add(s);
            }
            m.setSlots(slots);
            matches.add(m);
        }

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(seeded.size());
        plan.setMatches(matches);
        return plan;
    }
}
