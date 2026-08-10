package com.dance.street.game.engine.scoring;

import com.dance.street.game.engine.common.enums.MatchModeEnum;

import java.util.List;

/**
 * 打分策略。每种比赛模式(STANDARD/VOTING/RANKING)一个实现,
 * 负责从 {@link MatchScoreInput} 计算出本场所有参赛方的得分/排名/结果。
 *
 * <p>实现必须为无状态(线程安全),不依赖 Spring。</p>
 */
public interface ScoreStrategy {

    /** 该策略处理的比赛模式 */
    MatchModeEnum mode();

    /** 计算本场所有参赛方的得分/排名/结果 */
    List<MatchScoreResult> score(MatchScoreInput input);
}
