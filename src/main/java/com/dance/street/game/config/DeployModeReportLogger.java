package com.dance.street.game.config;

import org.dromara.common.redis.utils.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动时打印一次"实际生效"的运行配置。
 *
 * <p>环境后置处理器在日志系统初始化之前执行,它打的日志不会出现在应用日志里,
 * 因此这里补一条汇总,方便现场快速确认当前到底走了哪条路径:
 * 用的什么数据库、是连 Redis 还是本地广播。</p>
 *
 * <p>用 {@link SmartInitializingSingleton} 而非容器事件监听器,与项目内其他初始化器一致,
 * 且对 GraalVM native image 的 AOT 更友好(不依赖运行期解析泛型事件类型)。</p>
 */
@Component
public class DeployModeReportLogger implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DeployModeReportLogger.class);

    private final Environment environment;

    public DeployModeReportLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("运行配置: DEPLOY_MODE={} | 数据库={} | Redis={}",
            DeployModeResolver.resolve(environment),
            describeDataSource(environment.getProperty("spring.datasource.url", "")),
            RedisUtils.isAvailable() ? "已启用(支持多实例联动)" : "本地模式(SSE 进程内广播、无分布式锁)");
    }

    /** 只暴露数据源类型与地址(连接串不含密码,密码在独立属性里) */
    private static String describeDataSource(String url) {
        if (url == null || url.isBlank()) {
            return "未配置";
        }
        String type;
        if (url.startsWith("jdbc:sqlite:")) {
            type = "SQLite";
        } else if (url.startsWith("jdbc:mysql:")) {
            type = "MySQL";
        } else {
            return url;
        }
        int params = url.indexOf('?');
        return type + "(" + (params > 0 ? url.substring(0, params) : url) + ")";
    }
}
