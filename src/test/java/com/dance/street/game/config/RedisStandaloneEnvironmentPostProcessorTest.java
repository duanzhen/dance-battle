package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redis 单机模式回退:显式禁用或探测失败时排除 Redis 自动装配,
 * Redis 可用时不改动。探测地址用 127.0.0.1:1(连接必失败),不依赖外部 Redis。
 */
class RedisStandaloneEnvironmentPostProcessorTest {

    private final RedisStandaloneEnvironmentPostProcessor processor =
        new RedisStandaloneEnvironmentPostProcessor();

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    @Test
    void addsExcludesWhenExplicitlyDisabled() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.redis.enabled", "false");

        processor.postProcessEnvironment(env, null);

        String excludes = env.getProperty(EXCLUDE_KEY);
        assertNotNull(excludes);
        assertTrue(excludes.contains("org.redisson.spring.starter.RedissonAutoConfigurationV4"));
        assertTrue(excludes.contains("org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"));
    }

    @Test
    void addsExcludesWhenRedisUnreachable() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.data.redis.host", "127.0.0.1")
            .withProperty("spring.data.redis.port", "1");

        processor.postProcessEnvironment(env, null);

        assertNotNull(env.getProperty(EXCLUDE_KEY));
    }

    @Test
    void mergesWithExistingExcludes() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.redis.enabled", "false")
            .withProperty(EXCLUDE_KEY, "com.example.MyAutoConfig");

        processor.postProcessEnvironment(env, null);

        String excludes = env.getProperty(EXCLUDE_KEY);
        assertTrue(excludes.contains("com.example.MyAutoConfig"));
        assertTrue(excludes.contains("org.redisson.spring.starter.RedissonAutoConfigurationV4"));
    }

}
