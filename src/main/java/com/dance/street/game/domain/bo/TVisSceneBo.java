package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TVisScene;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 可视化场景配置业务对象 t_vis_scene
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TVisScene.class, reverseConvertGenerate = false)
public class TVisSceneBo extends BaseEntity {

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
     * 场景名: 总决赛KV / 竖屏比分
     */
    @NotBlank(message = "场景名: 总决赛KV / 竖屏比分不能为空", groups = { AddGroup.class, EditGroup.class })
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
