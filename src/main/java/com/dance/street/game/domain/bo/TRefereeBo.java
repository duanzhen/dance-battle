package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TReferee;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 裁判业务对象 t_referee
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TReferee.class, reverseConvertGenerate = false)
public class TRefereeBo extends BaseEntity {

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
     * 头像URL
     */
    private String avatar;

    /**
     *
     */
    @NotBlank(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     * 权限: ["STAGE_1_GROUP_A"]
     */
    private String permissions;

    /**
     * 备注
     */
    private String remark;


}
