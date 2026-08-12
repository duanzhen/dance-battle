package com.dance.street.game.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 单机模式自动回退。
 *
 * <p>Redis 未配置/不可达时,应用仍可单机运行:排除 Redisson 与 Spring Data Redis
 * 自动装配(不再创建 RedissonClient),SSE 走进程内本地广播、登录限流走本地计数、
 * 控件排序锁退化为 JVM 锁——多实例联动能力相应关闭。</p>
 *
 * <p>触发条件:显式设置 {@code REDIS_ENABLED=false},或默认开启时连接探测失败
 * (Redis 未启动/主机不可达)。</p>
 */
public class RedisStandaloneEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RedisStandaloneEnvironmentPostProcessor.class);

    private static final String ENABLED_KEY = "app.redis.enabled";
    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";
    private static final int PROBE_TIMEOUT_MS = 2000;

    private static final List<String> REDIS_AUTO_CONFIGS = List.of(
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "org.redisson.spring.starter.RedissonAutoConfigurationV4",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Boolean enabled = environment.getProperty(ENABLED_KEY, Boolean.class, true);
        boolean explicitlyDisabled = !Boolean.TRUE.equals(enabled);
        if (!explicitlyDisabled && redisReachable(environment)) {
            return;
        }
        if (explicitlyDisabled) {
            log.info("Redis 已禁用(REDIS_ENABLED=false),以单机模式运行:SSE 本地广播、无分布式锁/跨实例联动");
        } else {
            log.warn("Redis 连接探测失败,自动进入单机模式:SSE 本地广播、无分布式锁/跨实例联动"
                + "(显式关闭可设 REDIS_ENABLED=false)");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
            "redisStandalone", Map.of(EXCLUDE_KEY, mergedExcludes(environment))));
    }

    /** 保留用户已有的排除项,合并去重后返回逗号分隔字符串 */
    private static String mergedExcludes(ConfigurableEnvironment environment) {
        Set<String> excludes = new LinkedHashSet<>(REDIS_AUTO_CONFIGS);
        String existing = environment.getProperty(EXCLUDE_KEY);
        if (existing != null && !existing.isBlank()) {
            for (String item : existing.split(",")) {
                if (!item.isBlank()) {
                    excludes.add(item.trim());
                }
            }
        }
        return String.join(",", excludes);
    }

    /** TCP 探测 Redis:能建立连接即视为可用(认证失败也说明服务在) */
    private static boolean redisReachable(ConfigurableEnvironment environment) {
        String host = environment.getProperty("spring.data.redis.host", "127.0.0.1");
        int port = environment.getProperty("spring.data.redis.port", Integer.class, 6379);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.warn("Redis 探测失败({}:{}),进入单机模式: {}", host, port, e.getMessage());
            return false;
        }
    }
}
