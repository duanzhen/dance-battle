package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TMatch;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 比赛场次业务对象 t_match
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TMatch.class, reverseConvertGenerate = false)
public class TMatchBo extends BaseEntity {

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
    private Long stageId;

    /**
     * 
     */
    private String name;

    /**
     * 显示分区:LEFT/RIGHT(淘汰赛上下半区)、CENTER(季军赛/排名赛)、
     * ZONE-n(海选第 n 圈)、G1..Gn(小组赛分组)
     */
    private String displayZone;

    /**
     * Y轴排序
     */
    private Long displayRow;

    /**
     * 
     */
    private Long displayCol;

    /**
     * 
     */
    @NotBlank(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private String status;

    /**
     * STANDARD, VOTING, RANKING
     */
    private String matchMode;

    /**
     * 
     */
    private String promotionRule;

    /**
     * 备注
     */
    private String remark;


}
