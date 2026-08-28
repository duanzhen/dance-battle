package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TTournament;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 赛事主业务对象 t_tournament
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TTournament.class, reverseConvertGenerate = false)
public class TTournamentBo extends BaseEntity {

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 赛事名称
     */
    @NotBlank(message = "赛事名称不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     * 封面图片URL
     */
    private String coverImage;

    /**
     * 详情
     */
    private String description;

    /**
     * 0:筹备 1:进行中 2:结束
     */
    private Long status;

    /**
     * 设计稿宽度
     */
    private Long logicalWidth;

    /**
     * 设计稿高度
     */
    private Long logicalHeight;

    /**
     * {"bgColor": "#000", "fontFamily": "Roboto"}
     */
    private String themeConfig;

    /**
     * 备注
     */
    private String remark;

    /**
     * 自动创建的裁判数量(非持久化字段:创建赛事时按此批量生成裁判)
     */
    private Integer refereeCount;

    /**
     * 自动创建的裁判姓名列表(非持久化字段:创建赛事时按姓名批量生成裁判);
     * 非空时优先于 refereeCount,为空回退按 refereeCount 生成默认名
     */
    private java.util.List<String> refereeNames;

}
