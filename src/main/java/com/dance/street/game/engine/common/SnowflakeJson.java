package com.dance.street.game.engine.common;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigInteger;

/**
 * 内部 JSON(rule_config / roster_config_json / promotion_rule / 待公布结果等)专用 ObjectMapper。
 *
 * <p>雪花 ID 是 19 位 Long,超过 JS 的 {@code Number.MAX_SAFE_INTEGER}(9007199254740991),
 * 一旦以 JSON 数字形式落库,前端 {@code JSON.parse} 会被四舍五入,导致 ID 匹配不上
 * (例如海选圈配置里的裁判 ID 显示成 ID 而不是裁判名)。</p>
 *
 * <p>这里把超出安全范围的 Long/long/BigInteger 写成字符串,与 Web 层响应序列化
 * ({@link com.dance.street.game.config.JacksonConfig})共用同一口径;
 * 解析方向不受影响,Jackson 会把 {@code "2099..."} 这样的字符串按 Long 读回。</p>
 *
 * @author duane
 */
public final class SnowflakeJson {

    private SnowflakeJson() {
    }

    /** 雪花 ID 安全序列化模块(Web 层全局 mapper 与内部 mapper 共用) */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("snowflake-long-as-string");
        module.addSerializer(Long.class, new LongAsStringSerializer());
        module.addSerializer(Long.TYPE, new LongAsStringSerializer());
        module.addSerializer(BigInteger.class, new LongAsStringSerializer());
        return module;
    }

    /** 内部配置读写用 mapper:所有超出 JS 安全范围的整型 ID 输出为字符串 */
    public static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(module()).build();
    }

    /**
     * 超出 JS 安全整数范围的 Long/BigInteger 序列化为字符串;
     * 范围内的数值仍写数字,不影响普通业务字段(名次、人数、分数等)。
     */
    public static class LongAsStringSerializer extends ValueSerializer<Number> {

        private static final long MAX_SAFE_INTEGER = 9007199254740991L;
        private static final long MIN_SAFE_INTEGER = -9007199254740991L;

        @Override
        public void serialize(Number value, JsonGenerator gen, SerializationContext ctxt) {
            long longValue = value.longValue();
            if (longValue > MIN_SAFE_INTEGER && longValue < MAX_SAFE_INTEGER) {
                gen.writeNumber(longValue);
            } else {
                gen.writeString(value.toString());
            }
        }
    }
}
