package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 轮次打分结果对象 t_round_score
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_round_score")
public class TRoundScore extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 
     */
    private Long tournamentId;

    /**
     * 
     */
    private Long roundId;

    /**
     * 
     */
    private Long competitorId;

    /**
     * 
     */
    private String action;

    /**
     * 
     */
    private Long refereeId;

    /**
     * 
     */
    private BigDecimal score;

    /**
     * 
     */
    private String dimension;

    /**
     * 备注
     */
    private String remark;


}
