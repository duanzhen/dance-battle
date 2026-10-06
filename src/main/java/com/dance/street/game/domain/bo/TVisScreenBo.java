package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TVisScreen;
import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 大屏屏幕配置业务对象 t_vis_screen
 *
 * @author duane
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TVisScreen.class, reverseConvertGenerate = false)
public class TVisScreenBo extends BaseEntity {

    /**
     * 主键
     */
    @NotNull(message = "不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 所属赛事
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long tournamentId;

    /**
     * 屏幕名
     */
    @NotBlank(message = "屏幕名不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     * 排序
     */
    private Long sortOrder;

    /**
     * 当前投射的场景ID
     */
    private Long currentSceneId;

    /**
     * 备注
     */
    private String remark;
}
