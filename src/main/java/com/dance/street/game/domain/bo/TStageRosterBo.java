package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 名单来源组追加/编辑请求。
 *
 * @author duane
 */
@Data
public class TStageRosterBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 来源赛段(NULL=外部签到/外卡) */
    private Long sourceStageId;

    /** 结果过滤:ADVANCE/ELIMINATED/WITHDRAWN/PENDING/ANY */
    private String resultFilter;

    private Integer rankBandStart;

    private Integer rankBandEnd;

    private String zoneFilter;

    private Integer roundFilter;

    private BigDecimal scoreMin;

    private BigDecimal scoreMax;

    /** 取人上限(0=不限) */
    private Integer quota;

    /** AUTO/MANUAL/STREAM */
    private String fillMode;

    private Integer priority;

    private String remark;

    /** 分组取数规则(多个规则并集;为空时按单一规则字段处理) */
    private List<TStageRosterGroupBo> groups;
}
