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
     * 直接来源赛段ID(apply 写入时记录)
     */
    private Long sourceStageId;

    /**
     * 名单快照写入行标记(apply 置 1;签到/手工行为 0)
     */
    private Long fromRoster;

    /**
     * 入场性质:ADVANCE/REVIVE/GUEST/MANUAL/CHECKIN
     */
    private String entryTag;

    /**
     * 参赛方类型(保留字段;系统统一按选手处理,多成员即组队)
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
