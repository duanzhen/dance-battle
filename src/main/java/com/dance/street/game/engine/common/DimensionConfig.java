package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单个评分维度配置(MULTI_DIM 打分时使用)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DimensionConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 维度标识,如 TECH/CREATE/SHOW,单维度时默认 MAIN */
    private String key;
    /** 维度名称 */
    private String name;
    /** 权重(aggregateRule=WEIGHTED 时使用) */
    private BigDecimal weight;
    /** 该维度满分 */
    private BigDecimal maxScore;
}
