package com.dance.street.game.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败 IP 限流:窗口内同一 IP 连续失败达到上限后锁定,防止暴力破解。
 *
 * <p>以 Redis 原子计数实现,窗口从第一次失败开始计时;登录成功后清除计数。
 * Redis 异常时降级放行(仅告警),不影响正常登录。</p>
 */
@Slf4j
@Component
public class LoginIpRateLimiter {

    private static final String KEY_PREFIX = "login:fail:ip:";

    /** 窗口内允许的最大连续失败次数 */
    @Value("${login.rate-limit.max-failures:5}")
    private int maxFailures;

    /** 失败计数窗口(分钟),窗口内达到上限后锁定至窗口结束 */
    @Value("${login.rate-limit.window-minutes:15}")
    private long windowMinutes;

    /** 是否已锁定(达到失败上限) */
    public boolean isBlocked(String ip) {
        try {
            return RedisUtils.getAtomicValue(key(ip)) >= Math.max(1, maxFailures);
        } catch (Exception e) {
            log.warn("登录限流检查失败,本次放行: {}", e.getMessage());
            return false;
        }
    }

    /** 记录一次登录失败;首次失败时开始计时窗口 */
    public void recordFailure(String ip) {
        try {
            String key = key(ip);
            long count = RedisUtils.incrAtomicValue(key);
            if (count == 1) {
                RedisUtils.expire(key, Duration.ofMinutes(Math.max(1, windowMinutes)));
            }
        } catch (Exception e) {
            log.warn("登录失败计数写入失败: {}", e.getMessage());
        }
    }

    /** 登录成功后清除失败计数 */
    public void clear(String ip) {
        try {
            RedisUtils.deleteObject(key(ip));
        } catch (Exception e) {
            log.warn("清除登录失败计数失败: {}", e.getMessage());
        }
    }

    /** 锁定提示(含剩余分钟数) */
    public String blockedMessage(String ip) {
        long ttlMs = 0;
        try {
            ttlMs = RedisUtils.getTimeToLive(key(ip));
        } catch (Exception ignored) {
        }
        long minutes = Math.max(1, (ttlMs + 59999) / 60000);
        return "登录失败次数过多，请 " + minutes + " 分钟后重试";
    }

    /**
     * 解析客户端 IP:优先取 X-Forwarded-For 第一个地址(反向代理场景),
     * 其次 X-Real-IP,最后回退 remoteAddr。
     */
    public String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String ip = comma > 0 ? xff.substring(0, comma) : xff;
            return ip.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String key(String ip) {
        return KEY_PREFIX + ip;
    }
}
