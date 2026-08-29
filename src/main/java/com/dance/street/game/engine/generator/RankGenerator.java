package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 排名赛对阵生成:所有参赛方进入同一场次(不分圈),
 * 每个参赛方一个轮次,由裁判逐选手按多个自定义维度打分,结算时按总分排名晋级。
 *
 * <p>排名赛本身不产生两两对决,因此只需一场承载全部选手,
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
        List<MatchPlan> matches = new ArrayList<>();

        // 排名赛不分圈:单场承载全部选手,按签到号码顺序逐选手轮次
        MatchPlan m = new MatchPlan();
        m.setName("排名赛");
        m.setDisplayZone("CENTER");
        m.setRound(1);
        m.setMatchIndex(0);
        m.setDisplayCol(1);
        m.setDisplayRow(0);
        m.setFinalMatch(true); // 按名额晋级下一赛段

        List<SlotPlan> slots = new ArrayList<>();
        for (int i = 0; i < seeded.size(); i++) {
            SlotPlan s = new SlotPlan();
            s.setSlotIndex(i + 1); // 上场顺序从1开始,与选手号对齐
            s.setCompetitorId(seeded.get(i));
            s.setBye(false);
            slots.add(s);
        }
        m.setSlots(slots);
        matches.add(m);

        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(seeded.size());
        plan.setMatches(matches);
        return plan;
    }
}
