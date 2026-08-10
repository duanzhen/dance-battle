package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 擂台赛总览:轮转队列(含每人积分) + 当前对决。
 *
 * <p>队列规则:胜者守擂(留在队首),败者排到队尾,其余保持相对顺序;
 * 队列状态由已结算场次回放推导,不额外持久化。</p>
 *
 * @author duane
 * @date 2026-08-09
 */
@Data
public class ArenaOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;

    private String stageName;

    private String status;

    /** 当前队列顺序(队首即擂主),含积分 */
    private List<CompetitorInfo> queue;

    /** 当前对决(无进行中时为空) */
    private MatchInfo currentMatch;

    /** 已结束对决场数 */
    private Long battleCount;

    @Data
    public static class CompetitorInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long competitorId;

        private String name;

        private String number;

        private String avatar;

        /** 累计胜场数(积分) */
        private Integer points;

        /** 队列位置,从 1 开始;1 = 擂主 */
        private Integer queueIndex;
    }

    @Data
    public static class MatchInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long matchId;

        private String matchName;

        private CompetitorInfo defender;

        private CompetitorInfo challenger;
    }
}
