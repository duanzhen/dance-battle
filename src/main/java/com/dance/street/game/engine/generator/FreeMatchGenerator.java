package com.dance.street.game.engine.generator;

import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 自由对抗生成器:不生成任何对阵。
 *
 * <p>该赛制的对手由线下抽签/指认确定,场次一律由手机导播台手动添加
 * (选两名选手 → 裁判判罚 → 记录结果),晋级者也由导播台手动勾选,系统只负责记录。</p>
 *
 * @author duane
 */
public class FreeMatchGenerator implements StageGenerator {

    @Override
    public StageModeEnum mode() {
        return StageModeEnum.FREE_MATCH;
    }

    @Override
    public BracketPlan generate(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        BracketPlan plan = new BracketPlan();
        plan.setBracketSize(0);
        plan.setMatches(new ArrayList<>());
        return plan;
    }
}
