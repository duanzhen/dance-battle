package com.dance.street.game.engine.scoring;

import com.dance.street.game.engine.common.OutcomeScore;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * STANDARD 模式:判胜负平。
 * <p>胜负直接由 {@link MatchScoreInput#getDirectOutcomes()} 给定(裁判/批量录入 WIN/LOSS/DRAW),
 * 总分按 {@code outcomeRules}(winScore/drawScore/lossScore)映射,用于排名与累计积分。</p>
 */
public class WinLossDrawStrategy implements ScoreStrategy {

    private static final BigDecimal DEFAULT_WIN = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_DRAW = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_LOSS = BigDecimal.ZERO;

    @Override
    public MatchModeEnum mode() {
        return MatchModeEnum.STANDARD;
    }

    @Override
    public List<MatchScoreResult> score(MatchScoreInput input) {
        ScoringConfig cfg = input.getScoringConfig();
        OutcomeScore rules = cfg != null ? cfg.getOutcomeRules() : null;
        BigDecimal win = rules != null && rules.getWinScore() != null ? rules.getWinScore() : DEFAULT_WIN;
        BigDecimal draw = rules != null && rules.getDrawScore() != null ? rules.getDrawScore() : DEFAULT_DRAW;
        BigDecimal loss = rules != null && rules.getLossScore() != null ? rules.getLossScore() : DEFAULT_LOSS;

        Map<Long, String> outcomes = input.getDirectOutcomes() != null ? input.getDirectOutcomes() : Map.of();
        // 1v1 语义兜底:恰好一个胜者时,其余未判定参赛方按负处理
        // (避免只提交胜方时漏写 LOSS,导致败者位置/积分/队列推导错乱)
        long winCount = outcomes.values().stream()
            .filter(MatchOutcomeEnum.WIN.getCode()::equals)
            .count();
        Map<Long, String> effective = new HashMap<>(outcomes);
        if (winCount == 1) {
            for (Long cid : input.getCompetitorIds()) {
                if (effective.get(cid) == null) {
                    effective.put(cid, MatchOutcomeEnum.LOSS.getCode());
                }
            }
        }
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (Long cid : input.getCompetitorIds()) {
            String o = effective.get(cid);
            BigDecimal sv;
            if (MatchOutcomeEnum.WIN.getCode().equals(o)) {
                sv = win;
            } else if (MatchOutcomeEnum.DRAW.getCode().equals(o)) {
                sv = draw;
            } else if (MatchOutcomeEnum.LOSS.getCode().equals(o)) {
                sv = loss;
            } else {
                sv = DEFAULT_LOSS; // 未判定视为 0 分
            }
            scores.put(cid, sv);
        }

        Map<Long, Integer> ranks = RankCalculator.rank(scores);
        List<MatchScoreResult> list = new ArrayList<>();
        for (Long cid : input.getCompetitorIds()) {
            MatchScoreResult r = new MatchScoreResult();
            r.setCompetitorId(cid);
            r.setScoreValue(scores.get(cid));
            r.setRankInMatch(ranks.get(cid));
            r.setOutcomeStatus(effective.get(cid)); // WIN/LOSS/DRAW,未判定为 null
            list.add(r);
        }
        return list;
    }
}
