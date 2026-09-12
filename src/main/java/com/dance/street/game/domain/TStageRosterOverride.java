package com.dance.street.game.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 名单人工覆盖表 t_stage_roster_override。
 *
 * <p>规则(groups)之外的人工决定全部收敛为覆盖 delta:
 * ADD_SOURCE(拉进某源行)/ADD_GUEST(无源外卡)/REMOVE(剔除某源行)/SEED(固定种子)。</p>
 *
 * @author duane
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stage_roster_override")
public class TStageRosterOverride extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** 赛事ID */
    private Long tournamentId;

    /** 目标赛段(冗余,便于按赛段清理) */
    private Long targetStageId;

    /** ADD_SOURCE/ADD_GUEST/REMOVE/SEED */
    private String op;

    /** ADD_SOURCE/REMOVE/SEED 引用的源赛段行 */
    private Long sourceCompetitorId;

    /** ADD_GUEST 关联选手 */
    private Long playerId;

    /** ADD_GUEST 无选手时的展示名 */
    private String guestName;

    /** ADD_GUEST 类型(保留字段;系统统一按选手处理) */
    private Long guestType;

    /** 外卡号码(空=快照时自动) */
    private String guestNumber;

    /** SEED/ADD_GUEST 指定种子位(空=自动) */
    private Long seedRank;

    /** 备注 */
    private String remark;
}
