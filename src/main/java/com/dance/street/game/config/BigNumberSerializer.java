package com.dance.street.game.config;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 超出 JS 安全整数范围的 Long/BigInteger 序列化为字符串,
 * 避免前端 JSON.parse 精度丢失(雪花ID 2e18 > Number.MAX_SAFE_INTEGER 9e15)。
 */
public class BigNumberSerializer extends ValueSerializer<Number> {

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
