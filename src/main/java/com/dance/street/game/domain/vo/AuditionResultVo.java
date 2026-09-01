package com.dance.street.game.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 海选赛段结果统一视图:原始海选成绩 + 二海/三海…加赛明细。
 * <p>统一口径:二海(同分加赛)分数只用于同分者决出晋级顺序,不进入主表总分;
 * 展示/导出/大屏组件一律消费本视图,不再各自聚合场次分数。</p>
 */
@Data
public class AuditionResultVo {

    /** 原始海选成绩(按最终排名升序,未排名排最后) */
    private List<CompetitorItem> competitors;

    /** 加赛明细:round=1 二海,2 三海… */
    private List<TiebreakerItem> tiebreakers;

    @Data
    public static class CompetitorItem {
        private Long competitorId;
        private String number;
        private String name;
        /** 所属圈(displayZone) */
        private String zone;
        /** 原始海选总分(二海分不计入) */
        private BigDecimal score;
        /** 原始场次内排名(同分并列) */
        private Long rankInMatch;
        /** ADVANCE / ELIMINATED / PENDING / WITHDRAWN */
        private String outcomeStatus;
        /** 赛段最终排名(二海后) */
        private Long finalRank;
        /** 各裁判原始打分(用于导出明细) */
        private List<RefereeScoreItem> refereeScores;
    }

    @Data
    public static class TiebreakerItem {
        /** 1=二海,2=三海… */
        private Integer round;
        private Long matchId;
        private String name;
        private String zone;
        /** 加赛参与方(按号码升序) */
        private List<CompetitorItem> competitors;
    }

    @Data
    public static class RefereeScoreItem {
        private Long refereeId;
        private String refereeName;
        private BigDecimal score;
    }
}
