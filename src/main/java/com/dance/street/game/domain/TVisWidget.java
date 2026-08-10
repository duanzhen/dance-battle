package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 场景控件元素对象 t_vis_widget
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_vis_widget")
public class TVisWidget extends TenantEntity {

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
    private Long sceneId;

    /**
     * 控件备注
     */
    private String name;

    /**
     * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE
     */
    private String type;

    /**
     * 
     */
    private String layoutConfig;

    /**
     * X坐标
     */
    private Long x;

    /**
     * Y坐标
     */
    private Long y;

    /**
     * 宽度
     */
    private Long w;

    /**
     * 高度
     */
    private Long h;

    /**
     * Z轴层级
     */
    private Long zIndex;

    /**
     * 是否可见：0-隐藏 1-显示
     */
    private Long visible;

    /**
     * 是否锁定：0-否 1-是（锁定后不可编辑）
     */
    private Long locked;

    /**
     * 
     */
    private String dataConfig;

    /**
     * 
     */
    private String renderConfig;

    /**
     * 备注
     */
    private String remark;


}
