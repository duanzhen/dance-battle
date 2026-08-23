package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 海选赛对阵生成:创建单场比赛,所有参赛方作为参与者全部进入同一场次。
 *
 * <p>海选赛本质是"评委打分制":多名裁判对同一批选手打分,按总分排名后前N名晋级。
 * 因此只需生成一场比赛,所有种子选手作为参与者(status=PENDING),
 * 后续由裁判端依次打分(累计写入 TRoundScore),最终由 completeStage 结算排名并晋级。</p>
 */
public class AuditionGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.AUDITION;
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
        // 随机抽取圈:先打乱再均分,选手随机分到各圈场次
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
                m.setName("海选赛");
                m.setDisplayZone("CENTER");
            } else {
                m.setName("海选赛-" + (c + 1) + "圈");
                m.setDisplayZone("ZONE-" + (c + 1));
            }
            m.setRound(1);
            m.setMatchIndex(c);
            m.setDisplayCol(1);
            m.setDisplayRow(c);
            m.setFinalMatch(true); // 每圈胜者晋级下一赛段

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
