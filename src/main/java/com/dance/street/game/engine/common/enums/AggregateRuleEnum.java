package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 分数汇总规则。用于多裁判/多维度交叉聚合(见 ScoreAggregator)。
 * <p>SUM=求和;AVG=平均;TRIMMED_MEAN=去掉最高最低后平均(评委赛常用,消除单裁判偏颇);
 * WEIGHTED=按维度权重加权。</p>
 */
@Getter
@AllArgsConstructor
public enum AggregateRuleEnum {

    SUM("SUM", "求和"),
    AVG("AVG", "平均值"),
    TRIMMED_MEAN("TRIMMED_MEAN", "去极值平均"),
    WEIGHTED("WEIGHTED", "加权");

    private final String code;
    private final String desc;

    public static AggregateRuleEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (AggregateRuleEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的汇总规则: {}", code);
    }
}
