package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 场次参赛人员记录对象 t_match_participant
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_match_participant")
public class TMatchParticipant extends TenantEntity {

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
    private Long matchId;

    /**
     * 
     */
    private Long competitorId;

    /**
     * 
     */
    private Long displaySlotIndex;

    /**
     * 座位类型:PLAYER=真人参赛方 / BYE=轮空 / PENDING=待上游填入的占位。
     * 轮空/待定行的 competitorId 为 null,座位仍然实体化,保证位置可还原。
     */
    private String slotKind;

    /**
     * 总分/票数
     */
    private BigDecimal scoreValue;

    /**
     * 本场排名
     */
    private Long rankInMatch;

    /**
     * 选手结果
     */
    private String outcomeStatus;

    /**
     * 备注
     */
    private String remark;


}
