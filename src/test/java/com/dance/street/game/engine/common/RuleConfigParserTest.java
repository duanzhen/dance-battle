package com.dance.street.game.engine.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RuleConfigParser / RuleConfigHolder 的 JSON 往返与容错测试(M1 验收)。
 * <p>根 pom surefire 按 @Tag 过滤(profiles.active),故标注 @Tag("local") 以便在默认环境执行。</p>
 */
@Tag("local")
class RuleConfigParserTest {

    @Test
    void parseAndToJson_knockoutMultiDim_roundTrip() {
        String json = """
            {
              "mode": "KNOCKOUT", "format": "BO1",
              "knockout": { "template": "QUARTER_FINAL", "teamsCount": 8, "advanceCount": 4 },
              "scoring": {
                "type": "MULTI_DIM", "matchMode": "RANKING",
                "aggregateRule": "WEIGHTED", "refereeAggregateRule": "AVG", "trimRatio": 0.1,
                "dimensions": [
                  { "key": "TECH", "name": "技术", "weight": 0.5, "maxScore": 100 },
                  { "key": "CREATE", "name": "创意", "weight": 0.3, "maxScore": 100 }
                ],
                "outcomeRules": { "winScore": 1, "drawScore": 0.5, "lossScore": 0 }
              },
              "transition": {}
            }
            """;

        RuleConfigHolder holder = RuleConfigParser.parse(json);
        assertNotNull(holder, "解析结果不应为 null");
        assertEquals("KNOCKOUT", holder.getMode());
        assertEquals("BO1", holder.getFormat());
        assertNotNull(holder.getKnockout());
        assertEquals(8, holder.getKnockout().getTeamsCount());
        assertEquals(4, holder.getKnockout().getAdvanceCount());
        assertEquals("MULTI_DIM", holder.getScoring().getType());
        assertEquals("WEIGHTED", holder.getScoring().getAggregateRule());
        assertEquals("AVG", holder.getScoring().getRefereeAggregateRule());
        assertEquals(2, holder.getScoring().getDimensions().size());
        // BigDecimal 数值比较(0.5 反序列化后精度可能不同,用 compareTo)
        assertEquals(0, new BigDecimal("0.5").compareTo(holder.getScoring().getDimensions().get(0).getWeight()));
        // 往返:再序列化后解析,关键字段保持
        String json2 = RuleConfigParser.toJson(holder);
        RuleConfigHolder holder2 = RuleConfigParser.parse(json2);
        assertEquals(holder.getMode(), holder2.getMode());
        assertEquals(holder.getKnockout().getTeamsCount(), holder2.getKnockout().getTeamsCount());
        assertEquals(holder.getScoring().getDimensions().size(), holder2.getScoring().getDimensions().size());
    }

    @Test
    void parse_nullOrBlank_returnsNull() {
        assertNull(RuleConfigParser.parse(null));
        assertNull(RuleConfigParser.parse(""));
        assertNull(RuleConfigParser.parse("   "));
    }

    @Test
    void parse_unknownFieldsIgnored() {
        // 前端 ruleConfig 可能附带后端未识别的字段,必须忽略而非抛错(@JsonIgnoreProperties)
        String json = "{ \"mode\": \"GROUP\", \"futureField\": 123, \"group\": { \"groupCount\": 4 } }";
        RuleConfigHolder holder = RuleConfigParser.parse(json);
        assertNotNull(holder);
        assertEquals("GROUP", holder.getMode());
        assertEquals(4, holder.getGroup().getGroupCount());
    }

    @Test
    void toJson_null_returnsNull() {
        assertNull(RuleConfigParser.toJson(null));
    }
}
