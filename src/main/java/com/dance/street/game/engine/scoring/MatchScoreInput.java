package com.dance.street.game.engine.scoring;

import lombok.Builder;
import lombok.Data;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.enums.MatchModeEnum;

import java.util.List;
import java.util.Map;

/**
 * 单场比赛打分输入。同时容纳两种数据形态:
 * <ul>
 *   <li>{@code rawScores}:明细分(投票/多维度多裁判),VOTING/RANKING 模式使用</li>
 *   <li>{@code directOutcomes}:直接判定的胜负平,STANDARD 模式使用(competitorId -> WIN/LOSS/DRAW)</li>
 * </ul>
 */
@Data
@Builder
public class MatchScoreInput {

    /** 比赛模式,决定走哪个打分策略 */
    private MatchModeEnum matchMode;

    /** 打分配置(ruleConfig.scoring) */
    private ScoringConfig scoringConfig;

    /** 本场所有参赛方 competitorId(含未打分/轮空者) */
    private List<Long> competitorIds;

    /** 明细分(VOTING/RANKING 用);STANDARD 模式可为空 */
    private List<TRoundScore> rawScores;

    /** 直接判定的胜负(STANDARD 用):competitorId -> WIN/LOSS/DRAW */
    private Map<Long, String> directOutcomes;
}
