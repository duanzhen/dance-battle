package com.dance.street.game.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 裁判-赛段关联表 t_referee_stage
 *
 * @author duane
 * @date 2026-01-06
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_referee_stage")
public class TRefereeStage extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long refereeId;

    private Long tournamentId;

    private Long stageId;
}
