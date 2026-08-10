package com.dance.street.game.engine.scoring;

import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ScoreAggregator 四种汇总规则的纯函数测试。
 */
@Tag("local")
class ScoreAggregatorTest {

    private static final BigDecimal TRIM = new BigDecimal("0.2");

    @Test
    void sum() {
        BigDecimal r = ScoreAggregator.aggregate(
            List.of(bd(1), bd(2), bd(3)), AggregateRuleEnum.SUM, null);
        assertValue(bd(6), r);
    }

    @Test
    void avg() {
        BigDecimal r = ScoreAggregator.aggregate(
            List.of(bd(1), bd(2), bd(3)), AggregateRuleEnum.AVG, null);
        assertValue(bd(2), r);
    }

    @Test
    void trimmedMean_dropsExtremes() {
        // [1,2,3,4,100],trimRatio=0.2 → 每端去 1 个 → [2,3,4] 平均 = 3
        BigDecimal r = ScoreAggregator.trimmedMean(
            List.of(bd(1), bd(2), bd(3), bd(4), bd(100)), TRIM);
        assertValue(bd(3), r);
    }

    @Test
    void trimmedMean_tooFewValues_fallsBackToAvg() {
        // n<=2 时无法去极值,直接平均
        BigDecimal r = ScoreAggregator.trimmedMean(List.of(bd(1), bd(100)), TRIM);
        assertValue(bd(50.5), r);
    }

    @Test
    void weightedSum() {
        BigDecimal r1 = ScoreAggregator.weightedSum(List.of(bd(10), bd(20)), List.of(bd("0.5"), bd("0.5")));
        assertValue(bd(15), r1);

        BigDecimal r2 = ScoreAggregator.weightedSum(List.of(bd(10), bd(20)), List.of(bd("0.3"), bd("0.7")));
        assertValue(bd(17), r2);
    }

    @Test
    void nullRule_defaultsToSum() {
        BigDecimal r = ScoreAggregator.aggregate(List.of(bd(1), bd(2), bd(3)), null, null);
        assertValue(bd(6), r);
    }

    @Test
    void emptyOrNull_returnsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(ScoreAggregator.aggregate(null, AggregateRuleEnum.SUM, null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(ScoreAggregator.aggregate(List.of(), AggregateRuleEnum.AVG, null)));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertValue(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " but got " + actual);
    }
}
