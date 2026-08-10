package com.dance.street.game.service;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.engine.scoring.MatchScoreResult;

import java.util.List;

/**
 * 多裁判累计打分场次服务(VOTING / RANKING)。
 *
 * <p>这类场次不能像 STANDARD 那样"提交即结算":每位裁判只提交自己的分数,
 * 场次保持 GAMING,由 completeStage 用全部裁判分数统一聚合出最终排名并结算。</p>
 *
 * @author duane
 */
public interface ITScoredMatchService {

    /**
     * 裁判提交打分:先删该裁判本轮旧分 → 写新分 → 用全部裁判分重算每个参赛方总分/排名并回写。
     * 场次状态保持 GAMING,不结算。
     *
     * @return 当前全部裁判汇总后的打分结果
     */
    List<MatchScoreResult> accumulateScores(TMatch match, TStage stage, SubmitResultBo bo);

    /**
     * 结算赛段内所有仍 GAMING 的 VOTING/RANKING 场次:
     * 用全部裁判分计算最终排名 → 按赛制处理后(淘汰填下游/小组写胜负)→ 场次 SETTLED。
     */
    void settleScoredMatches(Long stageId);
}
