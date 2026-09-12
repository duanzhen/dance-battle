package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 赛段名单视图(id = 赛段 id;名单已是赛段属性,无独立实体)。
 *
 * @author duane
 */
@Data
public class TStageRosterVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** = 赛段 id */
    private Long id;

    private Long tournamentId;

    private Long targetStageId;

    // ---- 首来源组冗余展示列(兼容流向图等旧展示;规则以 groups 为准) ----
    private Long sourceStageId;
    private String resultFilter;
    private Integer rankBandStart;
    private Integer rankBandEnd;
    private String zoneFilter;
    private Integer roundFilter;
    private BigDecimal scoreMin;
    private BigDecimal scoreMax;
    private Integer quota;
    private String fillMode;
    private Integer priority;

    /** 名单状态(展示用):CONFIRMED/SKIPPED/READY/WAIT_SOURCE */
    private String state;

    private String remark;

    /** 来源组(唯一事实源) */
    private List<TStageRosterGroupBo> groups;

    /** 名单人工覆盖 */
    private List<TStageRosterOverrideVo> overrides;
}
