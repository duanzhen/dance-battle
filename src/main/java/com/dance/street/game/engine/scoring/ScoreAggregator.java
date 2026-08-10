package com.dance.street.game.engine.scoring;

import com.dance.street.game.engine.common.enums.AggregateRuleEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 分数汇总工具(无状态,纯函数,易单测)。
 * <p>支持四种规则:SUM 求和 / AVG 平均 / TRIMMED_MEAN 去极值平均 / WEIGHTED 加权(由 weightedSum 专门处理)。</p>
 */
public final class ScoreAggregator {

    /** 内部统一小数精度,避免排名比较时精度抖动 */
    public static final int SCALE = 4;

    private ScoreAggregator() {
    }

    /**
     * 无权重汇总:SUM/AVG/TRIMMED_MEAN。WEIGHTED 在此退化为 AVG
     * (加权场景需要维度权重,请用 {@link #weightedSum})。
     *
     * @param values    待汇总的值(允许含 null,自动忽略)
     * @param rule      汇总规则,null 时按 SUM
     * @param trimRatio TRIMMED_MEAN 时每端去掉的比例(0~0.5),null 视为 0
     */
    public static BigDecimal aggregate(List<BigDecimal> values, AggregateRuleEnum rule, BigDecimal trimRatio) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        AggregateRuleEnum r = rule != null ? rule : AggregateRuleEnum.SUM;
        return switch (r) {
            case SUM -> sum(values);
            case AVG -> avg(values);
            case TRIMMED_MEAN -> trimmedMean(values, trimRatio);
            case WEIGHTED -> avg(values);
        };
    }

    /**
     * 加权求和:sum(value[i] * weight[i])。常用于多维度按权重合成(维度权重和通常为 1)。
     */
    public static BigDecimal weightedSum(List<BigDecimal> values, List<BigDecimal> weights) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal w = (weights != null && i < weights.size() && weights.get(i) != null)
                ? weights.get(i) : BigDecimal.ZERO;
            BigDecimal v = values.get(i) != null ? values.get(i) : BigDecimal.ZERO;
            total = total.add(v.multiply(w));
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal sum(List<BigDecimal> values) {
        return values.stream()
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum(nonNull).divide(BigDecimal.valueOf(nonNull.size()), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 去掉每端 trimRatio 比例(至少各 1 个,若总数足够)后取平均。评委赛常用,消除单裁判偏颇。
     */
    public static BigDecimal trimmedMean(List<BigDecimal> values, BigDecimal trimRatio) {
        List<BigDecimal> nonNull = values.stream()
            .filter(Objects::nonNull)
            .sorted()
            .toList();
        if (nonNull.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int n = nonNull.size();
        if (n <= 2) {
            return avg(nonNull);
        }
        double ratio = trimRatio != null ? trimRatio.doubleValue() : 0.0;
        int drop = (int) Math.ceil(n * ratio);
        drop = Math.min(drop, (n - 1) / 2);
        List<BigDecimal> kept = nonNull.subList(drop, n - drop);
        return avg(kept);
    }
}
