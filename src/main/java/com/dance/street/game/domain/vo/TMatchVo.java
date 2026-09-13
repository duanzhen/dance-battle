package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;



/**
 * 比赛场次视图对象 t_match
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TMatch.class)
public class TMatchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long id;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long tournamentId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long stageId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String name;

    /**
     * LEFT, RIGHT, CENTER
     */
    @ExcelProperty(value = "LEFT, RIGHT, CENTER")
    private String displayZone;

    /**
     * Y轴排序
     */
    @ExcelProperty(value = "Y轴排序")
    private Long displayRow;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long displayCol;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String status;

    /**
     * STANDARD, VOTING, RANKING
     */
    @ExcelProperty(value = "STANDARD, VOTING, RANKING")
    private String matchMode;

    /**
     * 槽位0(左侧)参赛方名称,列表展示用
     */
    private String leftName;

    /**
     * 槽位0(左侧)参赛方ID
     */
    private Long leftCompetitorId;

    /**
     * 槽位1(右侧)参赛方名称,列表展示用;无参赛方(轮空)时为 null
     */
    private String rightName;

    /**
     * 槽位1(右侧)参赛方ID
     */
    private Long rightCompetitorId;

    /**
     * 槽位0(左侧)参赛方是否本场胜者
     */
    private Boolean leftWin;

    /**
     * 槽位1(右侧)参赛方是否本场胜者
     */
    private Boolean rightWin;

    /**
     * 本场胜者名称;未结算/平局加赛中为 null
     */
    private String winnerName;

    /**
     * 结果公布模式:AUTO/MANUAL/DIRECTOR
     */
    private String publishMode;

    /**
     * 手动公布模式:裁判已判完、结果待导播台公布
     */
    private Boolean pendingPublish;

    /**
     * 手动公布模式暂存结果(competitorId -> WIN/LOSS/DRAW),公布后清空
     */
    private String resultJson;

    /**
     * 实时判罚:已投票裁判数
     */
    private Integer votedReferees;

    /**
     * 实时判罚:应投票裁判数
     */
    private Integer totalReferees;

    /**
     * 实时判罚:左方胜票数
     */
    private Integer leftVotes;

    /**
     * 实时判罚:右方胜票数
     */
    private Integer rightVotes;

    /**
     * 实时判罚:平局票数(裁判数)
     */
    private Integer drawVotes;

    /**
     * 实时判罚:各裁判判罚明细(一行一个裁判的结果,未判 vote 为 null)
     */
    private List<RefereeVoteInfo> refereeVotes;

    /**
     * 海选赛段:本场轮次评分明细(每轮对应一名选手);非海选赛段为 null
     */
    private List<MatchRoundScoreVo> roundScores;

    /**
     * 淘汰赛:本场各轮判罚明细(每轮两名参赛者 + 各裁判 LEFT/RIGHT/DRAW,未判为 null)
     */
    private List<RoundVoteInfo> roundVotes;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String promotionRule;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    @Data
    public static class RefereeVoteInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long refereeId;
        private String refereeName;
        /** LEFT/RIGHT/DRAW;未判为 null */
        private String vote;
    }

    @Data
    public static class RoundVoteInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long roundId;
        private Long roundSequence;
        private String status;
        /** DRAW=平局加赛轮(已结算且非最后一轮) */
        private String outcome;
        /** 本轮左方参赛者名 */
        private String leftName;
        /** 本轮右方参赛者名 */
        private String rightName;
        /** 本轮胜方:LEFT/RIGHT/DRAW(全票平或票数持平);未决为 null */
        private String winnerSide;
        /** 本轮各裁判判罚明细(按裁判固定一行) */
        private List<RefereeVoteInfo> refereeVotes;
    }

}
