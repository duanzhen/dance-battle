package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 比赛轮次对象 t_match_round
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_match_round")
public class TMatchRound extends TenantEntity {

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
    private Long matchId;

    /**
     * 
     */
    private Long roundSequence;

    /**
     * 本轮出场选手(海选赛用)
     */
    private Long competitorId;

    /**
     * 
     */
    private String status;

    /**
     * 备注
     */
    private String remark;


}
