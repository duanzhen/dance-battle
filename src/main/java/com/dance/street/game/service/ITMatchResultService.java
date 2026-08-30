package com.dance.street.game.service;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;

/**
 * 比赛结果提交服务(裁判台实时 / 管理端批量共用)。
 *
 * @author duane
 */
public interface ITMatchResultService {

    /**
     * 提交单场比赛结果:写明细分 → ScoringEngine 算分算排名 → 回写 participant →
     * 赛制特有后处理(淘汰填下游占位)→ 场次 SETTLED → 可选自动 complete 赛段。
     */
    MatchResultVo submitResult(SubmitResultBo bo);

    /**
     * 开始指定场次:PENDING → GAMING(轮次同步 GAMING)。
     * 赛段若尚未开始则随场次一起进入 GAMING;未指定的场次保持 PENDING,实现"跳过先开始指定场次"。
     */
    void startMatch(Long matchId);

    /**
     * 取消开始场次(误触回退):GAMING → PENDING,清空本场已提交分数/结果与轮次状态,
     * 用于导播台点错「开始」后还原为待开始。仅淘汰赛支持。
     */
    void cancelStartMatch(Long matchId);

    /** 回退单场结算(调试用):级联清下游占位、清本场分数与排名、场次回 GAMING */
    void resetMatch(Long matchId);

    /** 导播台确认公布结果(MANUAL 模式):用裁判判完暂存的结果结算场次 */
    com.dance.street.game.domain.vo.MatchResultVo publishResult(Long matchId);
}
