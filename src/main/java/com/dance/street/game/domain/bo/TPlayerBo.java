package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TPlayer;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 选手自然人业务对象 t_player
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TPlayer.class, reverseConvertGenerate = false)
public class TPlayerBo extends BaseEntity {

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
    @NotBlank(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     *
     */
    private String avatar;

    /**
     * 身份唯一标识
     */
    private String idCard;

    /**
     * 首个赛段参赛选手
     */
    private Long competitorId;

    /**
     * 标签: ["种子", "外卡"]
     */
    private String tags;

    /**
     * 备注
     */
    private String remark;


}
