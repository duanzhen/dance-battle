package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 可视化场景配置对象 t_vis_scene
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_vis_scene")
public class TVisScene extends TenantEntity {

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
     * 场景名: 总决赛KV / 竖屏比分
     */
    private String name;

    /**
     * 
     */
    private Long designWidth;

    /**
     * 
     */
    private Long designHeight;

    /**
     * 场景格式：DEFAULT / VERTICAL / CUSTOM
     */
    private String format;

    /**
     * 背景颜色
     */
    private String bgColor;

    /**
     * 场景排序
     */
    private Long sortOrder;

    /**
     * 备注
     */
    private String remark;


}
