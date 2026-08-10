package com.dance.street.game.engine.scoring;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个参赛方在本场的打分结果(由 ScoringEngine 计算产出,供 Service 回写 TMatchParticipant)。
 */
@Data
public class MatchScoreResult {

    /** 参赛方 competitorId */
    private Long competitorId;

    /** 汇总总分/票数(写入 score_value) */
    private BigDecimal scoreValue;

    /** 本场排名(写入 rank_in_match,同分并列) */
    private Integer rankInMatch;

    /** 本场结果:WIN/LOSS/DRAW(STANDARD 模式);其他模式为 null,由 Resolver 按排名决定晋级 */
    private String outcomeStatus;
}
