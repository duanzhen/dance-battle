package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 参赛成员关联对象 t_competitor_member
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_competitor_member")
public class TCompetitorMember extends TenantEntity {

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
    private Long competitorId;

    /**
     * 
     */
    private Long playerId;

    /**
     * CAPTAIN, MEMBER
     */
    private String role;

    /**
     * 备注
     */
    private String remark;


}
