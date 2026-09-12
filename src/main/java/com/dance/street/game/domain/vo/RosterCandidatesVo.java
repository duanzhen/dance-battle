package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 名单候选(按来源组返回,供手动点选与预览)
 *
 * @author duane
 */
@Data
public class RosterCandidatesVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** = 赛段 id */
    private Long stageId;

    private String remark;

    private String state;

    private List<GroupCandidates> groups;

    @Data
    public static class GroupCandidates implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String label;

        /** 来源赛段ID(NULL=外部/签到) */
        private Long sourceStageId;

        private String resultFilter;

        private String zone;

        private Integer rankStart;

        private Integer rankEnd;

        private Boolean rankByZone;

        private Integer round;

        private BigDecimal scoreMin;

        private BigDecimal scoreMax;

        private List<TCompetitorVo> competitors;
    }
}
