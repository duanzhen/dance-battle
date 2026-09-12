package com.dance.street.game.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * 部署模式解析:{@code DEPLOY_MODE=auto|standalone|distributed}。
 *
 * <p>用于让部署方"说清楚意图",而不是依赖启动时的探测结果:</p>
 * <ul>
 *   <li>{@code auto}:沿用原有行为——MySQL/Redis 探测失败时自动降级为 SQLite/单机;</li>
 *   <li>{@code standalone}:显式单机,直接用 SQLite + 进程内 SSE,<b>完全跳过</b> MySQL/Redis 探测与降级;</li>
 *   <li>{@code distributed}:显式多实例,要求 MySQL + Redis 可用,连不上直接启动失败,不静默降级。</li>
 * </ul>
 *
 * <p>未显式配置时的默认值按运行形态区分:<b>GraalVM native 可执行文件默认 {@code standalone}</b>
 * (SQLite + 本地 SSE,零外部依赖,适合展会/现场单机),<b>JAR 默认 {@code auto}</b>
 * (保持既有部署行为不变)。两种形态都可以用 {@code DEPLOY_MODE} 覆盖。</p>
 *
 * <p>细粒度开关({@code DB_TYPE}、{@code REDIS_ENABLED}、{@code DB_FALLBACK_SQLITE})优先于本开关,
 * 便于"SQLite + Redis 多实例大屏"这类混搭部署。</p>
 */
final class DeployModeResolver {

    static final String KEY = "app.deploy-mode";
    static final String AUTO = "auto";
    static final String STANDALONE = "standalone";
    static final String DISTRIBUTED = "distributed";

    /** GraalVM Native Image 运行时标记属性({@code ImageInfo} 同源) */
    private static final String IMAGE_CODE_KEY = "org.graalvm.nativeimage.imagecode";

    private static final Logger log = LoggerFactory.getLogger(DeployModeResolver.class);

    private DeployModeResolver() {
    }

    /** 归一化部署模式;未配置时按运行形态取默认值,非法值按 {@link #AUTO} 处理 */
    static String resolve(Environment environment) {
        String raw = environment.getProperty(KEY);
        if (raw == null || raw.isBlank()) {
            return defaultMode();
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (AUTO.equals(mode) || STANDALONE.equals(mode) || DISTRIBUTED.equals(mode)) {
            return mode;
        }
        log.warn("未知 DEPLOY_MODE={},按 auto 处理(可选:auto/standalone/distributed)", raw);
        return AUTO;
    }

    /**
     * 未配置 {@code DEPLOY_MODE} 时的默认模式。
     *
     * <p>native 可执行文件跑在展会/现场单机居多,默认 {@code standalone}(SQLite + 本地 SSE);
     * JAR 保持 {@code auto},不改变既有部署行为。</p>
     */
    static String defaultMode() {
        return isNativeImage() ? STANDALONE : AUTO;
    }

    /** 是否运行在 GraalVM Native Image 中(构建期/运行期均返回 true) */
    static boolean isNativeImage() {
        if (System.getProperty(IMAGE_CODE_KEY) != null) {
            return true;
        }
        // 兜底:部分版本/裁剪场景不带 imagecode 属性,但 VM 名仍为 Substrate VM
        String vmName = System.getProperty("java.vm.name", "");
        return vmName.contains("Substrate");
    }

    static boolean isStandalone(Environment environment) {
        return STANDALONE.equals(resolve(environment));
    }

    static boolean isDistributed(Environment environment) {
        return DISTRIBUTED.equals(resolve(environment));
    }
}
