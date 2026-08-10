package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单条原始打分明细(投票/多维度多裁判模式使用)。
 *
 * @author duane
 * @date 2026-08-09
 */
@Data
public class ScoreEntryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "参赛方ID不能为空")
    private Long competitorId;

    /** 维度(MULTI_DIM 时如 TECH/SHOW;单维度默认 MAIN) */
    private String dimension;

    @NotNull(message = "分数不能为空")
    private BigDecimal score;

    /** 打分裁判ID(多裁判必填) */
    private Long refereeId;

    /** 动作:SCORE / VOTE(默认 SCORE) */
    private String action;
}
