package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单个参赛方的本场结算结果(提交结果接口返回)。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class ParticipantResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long competitorId;

    private BigDecimal scoreValue;

    private Integer rankInMatch;

    /** 本场结果:WIN/LOSS/DRAW(STANDARD);其他模式为 null */
    private String outcomeStatus;
}
