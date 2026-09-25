package com.dance.street.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.dance.street.game.engine.common.SnowflakeJson;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * Jackson 3 全局序列化配置:
 * Long/long/BigInteger 超出 JS 安全整数时输出字符串(与内部配置 JSON 共用 {@link SnowflakeJson} 口径),
 * BigDecimal 输出字符串。
 *
 * @author duane
 */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule longNumberToStringModule() {
        SimpleModule module = SnowflakeJson.module();
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        return module;
    }
}
