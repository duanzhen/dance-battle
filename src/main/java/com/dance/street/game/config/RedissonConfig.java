package com.dance.street.game.config;

import org.redisson.config.SingleServerConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 连接定制:REDIS_PASSWORD 为空时不发送 AUTH(空密码场景)。
 * 避免 Redisson 把空字符串当成密码去认证,导致 Redis 无密码时报错。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonAutoConfigurationCustomizer() {
        return config -> {
            // redisson-spring-boot-starter 把密码设在 Config 层(Config.setPassword),
            // 空密码时置 null 跳过 AUTH
            if (config.getPassword() == null || config.getPassword().isBlank()) {
                config.setPassword(null);
            }
            SingleServerConfig singleServerConfig = config.useSingleServer();
            if (singleServerConfig.getPassword() == null || singleServerConfig.getPassword().isBlank()) {
                singleServerConfig.setPassword(null);
            }
        };
    }
}
