package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 打分配置(ruleConfig.scoring)。决定单场比赛如何从原始打分(可能多裁判×多维度)汇总出总分与排名。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoringConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 打分类型,见 ScoreTypeEnum */
    private String type;
    /** 比赛模式,见 MatchModeEnum(生成 TMatch 时写入 match_mode) */
    private String matchMode;
    /** 维度间/总分汇总规则,见 AggregateRuleEnum;TRIMMED_MEAN 时配合 trimRatio */
    private String aggregateRule;
    /** 多裁判间汇总规则,见 AggregateRuleEnum;多裁判时把同维度多裁判分先合成单一维度分 */
    private String refereeAggregateRule;
    /** TRIMMED_MEAN 去极值比例,默认 0.1 */
    private BigDecimal trimRatio;
    /** 多维度配置(type=MULTI_DIM 时使用) */
    private List<DimensionConfig> dimensions;
    /** 胜负平对应积分(WIN_LOSS_DRAW 时使用) */
    private OutcomeScore outcomeRules;
}
