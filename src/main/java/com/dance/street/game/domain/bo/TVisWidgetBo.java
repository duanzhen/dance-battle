package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TVisWidget;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 场景控件元素业务对象 t_vis_widget
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TVisWidget.class, reverseConvertGenerate = false)
public class TVisWidgetBo extends BaseEntity {

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long tournamentId;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long sceneId;

    /**
     * 控件备注
     */
    private String name;

    /**
     * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE
     */
    @NotBlank(message = "BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE不能为空", groups = { AddGroup.class, EditGroup.class })
    private String type;

    /**
     * 
     */
    @NotBlank(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
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
