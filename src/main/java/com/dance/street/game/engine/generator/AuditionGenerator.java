package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 海选赛对阵生成:只按配置建立「圈」结构(空圈),不分配任何选手。
 *
 * <p>海选赛本质是"评委打分制":一个圈一个场次,圈内选手由签到环节落位
 * (见生命周期服务的 appendStageCompetitor,按客户端指定的目标圈写入
 * participant + round),裁判逐人打分,completeStage 按总分排名晋级。</p>
 *
 * <p><b>生成器不再把选手按号码或随机塞进各圈。</b>否则同一批号码会因为
 * 「先建空圈再逐个签到」和「全部签完再生成对阵」的调用顺序不同,得到不同的分圈结果;
 * 落圈权统一归客户端,后端只负责建好空的圈结构。</p>
 */
public class AuditionGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.AUDITION;
    }

    @Override
    public BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        int circles = 1;
        if (ruleConfig != null && ruleConfig.getCircles() != null) {
            circles = Math.max(1, ruleConfig.getCircles());
        }
        List<MatchPlan> matches = new ArrayList<>();

        // 每圈一个场次,全部为空圈:圈结构在签到/抽号前即存在,落圈由签到写入
        for (int c = 0; c < circles; c++) {
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
            m.setSlots(new ArrayList<>());
            matches.add(m);
        }

        BracketPlan plan = new BracketPlan();
        // 生成器不再持有参赛方,故不计对阵规模(落圈后由签到写入)
        plan.setBracketSize(0);
        plan.setMatches(matches);
        return plan;
    }
}
