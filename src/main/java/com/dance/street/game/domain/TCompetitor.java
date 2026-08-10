package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 参赛单位对象 t_competitor
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_competitor")
public class TCompetitor extends TenantEntity {

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
     *
     */
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
