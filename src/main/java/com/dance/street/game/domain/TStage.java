package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 赛段流程对象 t_stage
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stage")
public class TStage extends TenantEntity {

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
     * 上一赛段ID
     */
    private Long prevStageId;

    /**
     * 下一赛段ID (可修改以实现途中变轨)
     */
    private Long nextStageId;

    /**
     * 父ID (用于同分加赛)
     */
    private Long parentStageId;

    /**
     * 赛段名称
     */
    private String name;

    /**
     * AUDITION, KNOCKOUT, FFA, GROUP
     */
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
     * 起始队伍数量
     */
    private Long teamCountStart;

    /**
     * 晋级队伍数量
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
