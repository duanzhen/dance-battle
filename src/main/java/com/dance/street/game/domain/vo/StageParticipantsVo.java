package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 赛段参赛选手(大屏「参赛选手」控件用)。
 *
 * <p>口径唯一、按赛段取人,不掺业务判断:</p>
 * <ul>
 *   <li><b>名单已物化</b>(确认晋级)→ 读 {@code t_competitor},就是本赛段真实参赛选手;</li>
 *   <li><b>尚未物化</b> → 读中间层名单 {@code t_stage_roster_entry},包含还没落位的人
 *       ({@code seedRank=null, holding=true})。</li>
 * </ul>
 *
 * <p>因此大屏在任何时刻都能显示"目前这个赛段有哪些人",不会出现"名单还没确认就整块空白"。</p>
 *
 * @author duane
 */
@Data
public class StageParticipantsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;

    private String stageName;

    private String stageMode;

    private String status;

    /** COMPETITOR=已物化(读真实参赛方) / ROSTER=未物化(读中间态名单) */
    private String source;

    /** 计划人数(0=不限) */
    private Integer capacity;

    /** 已落位人数(有座位号) */
    private Integer seatedCount;

    /** 待落位人数:多入口汇合时先进待落位区,还没拖到座位 */
    private Integer holdingCount;

    private List<Participant> items = new ArrayList<>();

    @Data
    public static class Participant implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String name;

        private String number;

        /** 座位号;未落位为 null */
        private Long seedRank;

        /** true=还没落位(多入口汇合待导播拖动) */
        private Boolean holding;

        /** 入场性质:ADVANCE/REVIVE/GUEST/CHECKIN */
        private String entryTag;

        /** SOURCE=来源赛段 / GUEST=外卡 */
        private String refType;

        /** 0=个人 1=队伍 */
        private Long type;

        private String avatar;

        /** 直接来源赛段(展示"来自哪一段"用) */
        private Long sourceStageId;

        /** 物化后才有:ADVANCE/ELIMINATED/PENDING */
        private String outcomeStatus;
    }
}
