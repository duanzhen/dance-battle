package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 名单人工覆盖创建/更新业务对象。
 *
 * @author duane
 */
@Data
public class TStageRosterOverrideBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
