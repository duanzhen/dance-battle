package com.dance.street.game.engine.scoring;

import org.dromara.common.core.exception.ServiceException;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 打分引擎入口。按 {@link MatchModeEnum} 分派到对应 {@link ScoreStrategy}。
 *
 * <p>策略由 Spring 自动收集:每个 {@link ScoreStrategy} 实现标注 {@code @Component} 即被注册,
 * 新增比赛模式只加一个实现类,无须改动本类(与 {@code StageSettlerRegistry} 同一套机制)。
 * 引擎与策略均无状态,单测可直接 {@code new}(见 {@code ScoringEngineTest})。</p>
 *
 * <p>{@link #uncoveredModes()} 供测试断言:每个 {@link MatchModeEnum} 都必须有打分策略,漏实现直接暴露。</p>
 *
 * @author duane
 */
@Component
public class ScoringEngine {

    private final Map<MatchModeEnum, ScoreStrategy> strategies;

    public ScoringEngine(List<ScoreStrategy> strategies) {
        Map<MatchModeEnum, ScoreStrategy> map = new EnumMap<>(MatchModeEnum.class);
        for (ScoreStrategy strategy : strategies) {
            ScoreStrategy previous = map.put(strategy.mode(), strategy);
            if (previous != null) {
                throw new ServiceException("比赛模式[{}]注册了多个打分策略:{} 与 {}",
                    strategy.mode().getCode(), previous.getClass().getSimpleName(),
                    strategy.getClass().getSimpleName());
            }
        }
        this.strategies = Map.copyOf(map);
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

    /** 尚未注册打分策略的比赛模式(启动自检/测试断言用)。 */
    public List<String> uncoveredModes() {
        return Arrays.stream(MatchModeEnum.values())
            .filter(mode -> !strategies.containsKey(mode))
            .map(MatchModeEnum::getCode)
            .toList();
    }
}
