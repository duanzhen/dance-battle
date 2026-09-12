package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小组赛配置(ruleConfig.group)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分组数量 */
    private Integer groupCount;
    /** 每组选手数 */
    private Integer teamsPerGroup;
    /** 胜积分 */
    private Integer winPoints;
    /** 平积分 */
    private Integer drawPoints;
    /** 负积分 */
    private Integer lossPoints;
    /** 每组晋级数 */
    private Integer advancePerGroup;
    /** 循环类型 SINGLE/DOUBLE,默认 SINGLE */
    private String roundRobinType;
}
