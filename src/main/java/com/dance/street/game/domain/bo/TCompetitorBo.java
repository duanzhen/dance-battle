package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TCompetitor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 参赛单位业务对象 t_competitor
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TCompetitor.class, reverseConvertGenerate = false)
public class TCompetitorBo extends BaseEntity {

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
     * 上一阶段的CompetitorID
     */
    private Long sourceCompetitorId;

    /**
     * 0:个人, 1:队伍
     */
    private Long type;

    /**
     * 展示名称
     */
    private String name;

    /**
     * 选手号
     */
    private String number;

    /**
     * 本赛段初始种子顺位
     */
    private Long seedRank;

    /**
     * 本赛段最终排名
     */
    private Long finalRank;

    /**
     * 本赛段结果
     */
    private String outcomeStatus;

    /**
     * 备注
     */
    private String remark;


}
