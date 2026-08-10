package com.dance.street.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Jackson 3 全局序列化配置:
 * Long/long/BigInteger 超出 JS 安全整数时输出字符串,BigDecimal 输出字符串,
 */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule longNumberToStringModule() {
        SimpleModule module = new SimpleModule("LongNumberToStringModule");
        module.addSerializer(Long.class, new BigNumberSerializer());
        module.addSerializer(Long.TYPE, new BigNumberSerializer());
        module.addSerializer(BigInteger.class, new BigNumberSerializer());
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        return module;
    }
}
