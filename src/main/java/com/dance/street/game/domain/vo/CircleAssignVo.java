package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 海选随机抽取圈结果:一场(一圈)对应的选手分配。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class CircleAssignVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long matchId;

    private String matchName;

    private String displayZone;

    private List<CompetitorInfo> competitors;

    @Data
    public static class CompetitorInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long competitorId;

        private String name;

        private String number;

        private Long slotIndex;
    }
}
