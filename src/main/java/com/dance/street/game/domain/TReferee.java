package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 裁判对象 t_referee
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_referee")
public class TReferee extends TenantEntity {

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
     * 头像URL
     */
    private String avatar;

    /**
     * 
     */
    private String name;

    /**
     * 登录凭证
     */
    private String authKey;

    /**
     * 权限: ["STAGE_1_GROUP_A"]
     */
    private String permissions;

    /**
     * 备注
     */
    private String remark;


}
