package com.dance.street.game.config;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单机模式验证:不配置 Redis(REDIS_ENABLED=false)时应用可完整启动,
 * 不创建 RedissonClient,Redis 操作走本地兜底(SSE 本地广播、登录限流本地计数)。
 */
@SpringBootTest(properties = "app.redis.enabled=false")
class GameApplicationStandaloneTests {

    @Autowired
    private LoginIpRateLimiter loginIpRateLimiter;

    @Test
    void contextStartsWithoutRedissonClient() {
        assertFalse(SpringUtils.containsBean("redissonClient"),
            "单机模式不应创建 RedissonClient");
        assertFalse(RedisUtils.isAvailable(), "单机模式 Redis 应不可用");
    }

    @Test
    void sseTopicDispatchesLocallyWithoutRedis() {
        AtomicReference<String> received = new AtomicReference<>();
        RedisUtils.subscribe("standalone:test:topic", String.class, received::set);

        RedisUtils.publish("standalone:test:topic", "hello-standalone", msg -> {
        });

        assertEquals("hello-standalone", received.get(), "无 Redis 时主题消息应本地直达");
    }

    @Test
    void loginRateLimitCountsLocally() {
        String ip = "203.0.113.7";
        loginIpRateLimiter.clear(ip);
        assertFalse(loginIpRateLimiter.isBlocked(ip));

        for (int i = 0; i < 5; i++) {
            loginIpRateLimiter.recordFailure(ip);
        }

        assertTrue(loginIpRateLimiter.isBlocked(ip), "本地计数达到上限后应锁定");

        loginIpRateLimiter.clear(ip);
        assertFalse(loginIpRateLimiter.isBlocked(ip), "清除后应解除锁定");
    }
}
