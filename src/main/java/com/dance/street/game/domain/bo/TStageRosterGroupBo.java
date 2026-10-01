package com.dance.street.game.domain.bo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 名单来源组"取数规则":一份名单可由多组规则并集取人。
 *
 * <p>典型场景:两圈海选的复活名单 =
 * 组1(圈1·名次9~24) + 组2(圈2·名次9~24)。</p>
 *
 * @author duane
 */
@Data
public class TStageRosterGroupBo {

    /** 来源组行 ID(表化后按 id 定位,不再用数组下标);新增时为空 */
    private Long id;

    /** 来源赛段(NULL=外部签到/外卡) */
    private Long sourceStageId;

    /** 结果过滤:ADVANCE/ELIMINATED/WITHDRAWN/PENDING/ANY */
    private String resultFilter;

    /** 圈/组过滤(如 ZONE-1);空 = 不限圈 */
    private String zone;

    /** 名次区间 */
    private Integer rankStart;

    private Integer rankEnd;

    /** true = 名次取"圈内名次"(participant.rank_in_match);false = 赛段全局名次(final_rank) */
    private Boolean rankByZone;

    /** 淘汰赛第几轮(display_row 过滤) */
    private Integer round;

    /** 分数区间(participant.score_value) */
    private BigDecimal scoreMin;

    private BigDecimal scoreMax;

    /** 本组填充方式:AUTO/MANUAL/STREAM */
    private String fillMode;

    /** 本组取人上限(0=不限) */
    private Integer quota;

    /** 取人顺序(小者先取);表化后由服务端按列表顺序维护(取代旧 JSON 里的 priority 死字段) */
    private Integer sortOrder;

    /** 1 = 建段时系统自动补的链式衔接(按出处判断,不再看字段长相) */
    private Integer generated;

    /**
     * 旧 JSON 时代按"字段长相"判断这条是不是系统自动生成的链式衔接(上一赛段·整单晋级·AUTO)。
     *
     * <p><b>只用于一次性数据搬迁</b>:新代码一律读 {@code generated} 列,不再看长相——
     * 出口面板配出来的自定义出口长相与默认组完全相同,按长相判断会把真实依赖误删。</p>
     */
    public boolean looksLikeGeneratedDefault() {
        return sourceStageId != null
            && (fillMode == null || "AUTO".equals(fillMode))
            && "ADVANCE".equals(resultFilter)
            && zone == null && round == null
            && rankStart == null && rankEnd == null
            && scoreMin == null && scoreMax == null
            && !Boolean.TRUE.equals(rankByZone);
    }

    /**
     * 组内排序键:FINAL_RANK/ZONE_RANK/ZONE_RANK_ROTATE/SCORE/NUMBER/RANDOM。
     * 空 = 自动(源赛段为多圈海选时按圈内名次轮转,否则按全局名次)。
     */
    private String orderBy;
}
