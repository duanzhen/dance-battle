package com.dance.street.game.engine.scoring;

import org.dromara.common.core.exception.ServiceException;
import com.dance.street.game.engine.common.enums.MatchModeEnum;

import java.util.List;
import java.util.Map;

/**
 * 打分引擎入口。按 {@link MatchModeEnum} 分派到对应 {@link ScoreStrategy}。
 *
 * <p>无状态、不依赖 Spring,可直接 {@code new ScoringEngine()} 使用(便于单测);Service 层持有单例字段即可。</p>
 */
public class ScoringEngine {

    private final Map<MatchModeEnum, ScoreStrategy> strategies;

    public ScoringEngine() {
        this.strategies = Map.of(
            MatchModeEnum.STANDARD, new WinLossDrawStrategy(),
            MatchModeEnum.VOTING, new TotalScoreStrategy(),
            MatchModeEnum.RANKING, new MultiDimStrategy()
        );
    }

    /**
     * 计算本场所有参赛方的得分/排名/结果。
     *
     * @param input 打分输入(模式 + 配置 + 原始分/直接胜负 + 参赛方列表)
     * @return 每个参赛方的得分结果(scoreValue / rankInMatch / outcomeStatus)
     */
    public List<MatchScoreResult> compute(MatchScoreInput input) {
        if (input == null || input.getMatchMode() == null) {
            throw new ServiceException("比赛模式不能为空");
        }
        ScoreStrategy strategy = strategies.get(input.getMatchMode());
        if (strategy == null) {
            throw new ServiceException("不支持的比赛模式: {}", input.getMatchMode().getCode());
        }
        return strategy.score(input);
    }
}
