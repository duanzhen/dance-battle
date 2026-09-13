package com.dance.street.game.engine.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部配置 JSON 的雪花 ID 精度保护:超出 JS 安全整数范围的 Long 必须写成字符串。
 */
@Tag("local")
class SnowflakeJsonTest {

    private final ObjectMapper mapper = SnowflakeJson.mapper();

    @Test
    void snowflakeId_writtenAsString() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snowflakeId", 2099166014161072130L);
        payload.put("refereeIds", java.util.List.of(2099166014161072131L, 2099166014161072132L));
        payload.put("advanceCount", 8L);

        String json = mapper.writeValueAsString(payload);

        assertTrue(json.contains("\"snowflakeId\":\"2099166014161072130\""), json);
        assertTrue(json.contains("\"2099166014161072131\""), json);
        // 安全范围内的普通数值保持数字,不影响名次/人数/分数字段
        assertTrue(json.contains("\"advanceCount\":8"), json);
    }

    @Test
    void stringId_parsedBackAsLong() {
        RuleConfigHolder holder = mapper.readValue(
            "{\"mode\":\"AUDITION\",\"circleRefereeIds\":[[\"2099166014161072130\"],[\"2099166014161072131\"]]}",
            RuleConfigHolder.class);

        assertEquals(2099166014161072130L, holder.getCircleRefereeIds().get(0).get(0));
        assertEquals(2099166014161072131L, holder.getCircleRefereeIds().get(1).get(0));
    }

    @Test
    void roundTrip_keepsIdExact() {
        RuleConfigHolder holder = new RuleConfigHolder();
        holder.setMode("AUDITION");
        holder.setCircleRefereeIds(java.util.List.of(java.util.List.of(2099166014161072131L)));

        String json = mapper.writeValueAsString(holder);
        RuleConfigHolder parsed = mapper.readValue(json, RuleConfigHolder.class);

        assertEquals(2099166014161072131L, parsed.getCircleRefereeIds().get(0).get(0));
    }
}
