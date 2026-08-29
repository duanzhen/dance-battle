package com.dance.street.game.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 裁判-场次(圈)关联对象 t_match_referee
 *
 * <p>海选分圈时记录每个圈(场次)对应的裁判,大屏晋级名单按圈展示裁判。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_match_referee")
public class TMatchReferee extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** 场次ID(海选一圈一场) */
    private Long matchId;

    /** 裁判ID */
    private Long refereeId;

    /** 赛事ID(冗余,方便查询) */
    private Long tournamentId;

    /** 备注 */
    private String remark;
}
