package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 胜负平对应积分(ScoringConfig.outcomeRules,WIN_LOSS_DRAW 打分时使用)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutcomeScore implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private BigDecimal winScore;
    private BigDecimal drawScore;
    private BigDecimal lossScore;
}
