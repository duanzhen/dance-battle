package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TStage;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 赛段流程业务对象 t_stage
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TStage.class, reverseConvertGenerate = false)
public class TStageBo extends BaseEntity {

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
     * 上一赛段ID
     */
    private Long prevStageId;

    /**
     * 下一赛段ID (可修改以实现途中变轨)
     */
    private Long nextStageId;

    /**
     * 新增赛段的插入位置:插在该赛段之后,空 = 插到链头。
     *
     * <p>新增走「意图」而不是「前后指针」:后端按本字段把新赛段接入链,
     * 客户端不需要(也不应该)自己算 prev/next。</p>
     *
     * <p>{@code prevStageId} 作为新增时的插入位置保留兼容(语义同本字段),
     * 但只在 {@code afterStageId} 为空时才生效。</p>
     */
    private Long afterStageId;

    /**
     * 父ID (用于同分加赛)
     */
    private Long parentStageId;

    /**
     * 32进16 / 复活赛
     */
    private String name;

    /**
     * AUDITION, KNOCKOUT, GROUP, ARENA, RANK
     */
    @NotBlank(message = "AUDITION, KNOCKOUT, GROUP, ARENA, RANK不能为空", groups = { AddGroup.class, EditGroup.class })
    private String stageMode;

    /**
     * 每队成员数量
     */
    private Long members;

    /**
     * 在大图中处于第几列 (X轴)
     */
    private Long visualColIndex;

    /**
     *
     */
    private String ruleConfig;

    /**
     * 状态
     */
    private String status;

    /**
     * 起始选手数量
     */
    private Long teamCountStart;

    /**
     * 晋级选手数量
     */
    private Long teamCountEnd;

    /**
     * 是否完成初始化配置：0-否 1-是
     */
    private Long isInitialized;

    /**
     * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
     */
    private String visualConfig;

    /**
     * 备注
     */
    private String remark;


}
