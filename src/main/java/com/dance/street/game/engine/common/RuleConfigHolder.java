package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 赛段规则配置(对应 TStage.rule_config JSON)。
 * <p>顶层承载赛制类型与轮次,子配置承载各赛制特有参数、打分规则、转场规则。</p>
 *
 * <pre>
 * {
 *   "mode": "KNOCKOUT", "format": "BO1",
 *   "knockout": { "template":"QUARTER_FINAL", "teamsCount":8, "advanceCount":4 },
 *   "scoring":  { "type":"MULTI_DIM", "matchMode":"RANKING", ... },
 *   "transition": { "seedOverrides": {...} }
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleConfigHolder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 赛制类型,见 {@link com.dance.street.game.engine.common.enums.StageModeEnum} */
    private String mode;

    /** 比赛格式 BO1/BO3/BO5(轮次维度,与打分正交) */
    private String format;

    /** 淘汰赛配置(mode=KNOCKOUT 时使用) */
    private KnockoutConfig knockout;

    /** 小组赛配置(mode=GROUP 时使用) */
    private GroupConfig group;

    /** 海选分圈数(mode=AUDITION 时使用,1=不分圈,全场一场) */
    private Integer circles;

    /** 分圈方式:true=随机抽取分场(签到后随机分到各圈场次),false=按号码顺序均分(仅生成时使用,不入库) */
    private Boolean randomSplit;

    /** 淘汰赛轮次序号(16强=1、8强=2、半决赛=3、决赛=4),仅生成场次命名时使用,不入库 */
    private Integer knockoutRound;

    /** 结果公布模式(排名赛等使用):AUTO=实时公布 / MANUAL=导播台手动公布 / BATCH=全部完成后一次性公布 */
    private String publishMode;

    /** 公布范围(BATCH 模式使用):ALL=公布全部排名 / TOP_N=只公布前 N 名晋级名单 */
    private String publishScope;

    /** 排名展示是否显示分数(赛段级配置,由排名展示组件读取) */
    private Boolean showScore;

    /** 排名展示分数显示方式:TOTAL=只显示总分 / DETAIL=总分+各维度分(赛段级配置) */
    private String scoreDisplay;

    /** 打分配置(决定每局如何判定胜负) */
    private ScoringConfig scoring;

    /** 转场配置(决定晋级如何触发) */
    private TransitionConfig transition;
}
