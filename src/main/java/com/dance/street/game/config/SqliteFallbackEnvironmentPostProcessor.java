package com.dance.street.game.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源模式解析:显式 SQLite、显式 MySQL,以及 MySQL 未配置/不可达时的 SQLite 自动回退。
 *
 * <p>可通过 {@code DB_TYPE=sqlite|mysql} 或 {@code DEPLOY_MODE=standalone|distributed}
 * 显式声明数据源模式:</p>
 * <ul>
 *   <li>{@code DB_TYPE=sqlite} / {@code DEPLOY_MODE=standalone}:不做 MySQL 探测,
 *       直接把数据源指向 SQLite 文件库(默认 {@code jdbc:sqlite:./data/game.db},
 *       可用 {@code SQLITE_FALLBACK_URL} 覆盖),启动更快;</li>
 *   <li>{@code DB_TYPE=mysql} / {@code DEPLOY_MODE=distributed}:不启用回退,
 *       MySQL 不可达时直接启动失败并给出明确原因,便于尽早发现配置问题。</li>
 * </ul>
 *
 * <p>以上均未配置(auto)时保持原有行为:若数据源为 MySQL,先用短超时探测一次连通性;连接失败
 * (MySQL 未启动、主机不可达、未装驱动等)则把 {@code spring.datasource.*}
 * 切换为 SQLite 文件库,后续 Hikari、MyBatis-Plus 方言、schema 自检都会
 * 自动按 SQLite 走,无需改任何业务代码。</p>
 *
 * <p>自动回退可通过环境变量关闭:{@code DB_FALLBACK_SQLITE=false}。
 * SQLite 连接会统一补齐两组参数(已显式设置的不覆盖):</p>
 *
 * <ul>
 *   <li><b>日期</b>{@code date_class=text&date_string_format=...}:与建表脚本的 TEXT 日期列一致,
 *       避免日期字段按整数写入后无法解析;</li>
 *   <li><b>并发护栏</b>{@code journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE
 *       &synchronous=NORMAL}:SQLite 是单写者库,而应用是多连接(Hikari)且大量事务
 *       「先读后写」。默认配置(delete journal + 3s 忙等 + 延迟事务)下,一个事务读完再想写时,
 *       若别的连接正持锁,SQLite 的死锁检测会<b>立即</b>抛 SQLITE_BUSY(忙等参数根本不生效);
 *       WAL 让读者不挡写者,IMMEDIATE 让事务一开始就取写锁从而走忙等重试,而不是失败。</li>
 * </ul>
 *
 * <p>这两组参数对 {@code DB_URL}、{@code SQLITE_FALLBACK_URL}、Docker/native 里写死的
 * SQLite 连接一视同仁——只要最终数据源是 SQLite 就会被补齐,不需要各部署形态各配一遍。</p>
 *
 * @author duane
 */
public class SqliteFallbackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqliteFallbackEnvironmentPostProcessor.class);

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL_KEY = "spring.datasource.url";
    private static final String USERNAME_KEY = "spring.datasource.username";
    private static final String PASSWORD_KEY = "spring.datasource.password";
    private static final String FALLBACK_ENABLED_KEY = "app.db.fallback-to-sqlite";
    private static final String FALLBACK_URL_KEY = "app.db.sqlite-fallback-url";
    private static final String DB_TYPE_KEY = "app.db.type";
    private static final String DEFAULT_SQLITE_URL = "jdbc:sqlite:./data/game.db";
    private static final String DB_TYPE_AUTO = "auto";
    private static final String DB_TYPE_SQLITE = "sqlite";
    private static final String DB_TYPE_MYSQL = "mysql";
    /** 与 sql/game_db.sqlite.sql 中 TEXT 日期列匹配的 sqlite-jdbc 日期参数 */
    private static final String SQLITE_DATE_PARAMS = "date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS";
    /**
     * SQLite 单机并发护栏参数:
     * <ul>
     *   <li>{@code journal_mode=WAL}:读者不阻塞写者(默认的 delete 模式下,一个长读事务就能把写者卡住);</li>
     *   <li>{@code busy_timeout=10000}:拿不到锁时忙等 10s 再报错,而不是立刻失败;</li>
     *   <li>{@code transaction_mode=IMMEDIATE}:Spring 开事务(setAutoCommit(false))时直接
     *       {@code BEGIN IMMEDIATE} 取写锁——延迟事务「先读后写」的锁升级不走忙等,会立刻 SQLITE_BUSY;</li>
     *   <li>{@code synchronous=NORMAL}:WAL 下的常规持久性设置,避免每次提交都 fsync。</li>
     * </ul>
     */
    private static final String SQLITE_CONCURRENCY_PARAMS =
        "journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE&synchronous=NORMAL";
    private static final int PROBE_TIMEOUT_MS = 3000;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbType = resolveDbType(environment);
        boolean explicitSqlite = DB_TYPE_SQLITE.equals(dbType);
        boolean explicitMysql = DB_TYPE_MYSQL.equals(dbType);
        boolean standalone = DeployModeResolver.isStandalone(environment);
        boolean distributed = DeployModeResolver.isDistributed(environment);

        String url = environment.getProperty(URL_KEY);

        // 1) 显式 SQLite(或单机部署):直接切库,不做任何 MySQL 探测
        if (explicitSqlite || (!explicitMysql && standalone)) {
            if (explicitSqlite && distributed) {
                log.warn("DB_TYPE=sqlite 与 DEPLOY_MODE=distributed 冲突,按 DB_TYPE=sqlite 使用本地 SQLite");
            }
            switchToSqlite(environment, url, explicitSqlite ? "DB_TYPE=sqlite" : "DEPLOY_MODE=standalone");
            return;
        }

        if (url == null || url.isBlank()) {
            return;
        }

        // 直接使用 SQLite(DB_URL=jdbc:sqlite:...):补齐日期参数,保证时间字段读写一致
        if (url.startsWith("jdbc:sqlite:")) {
            if (distributed) {
                log.warn("DB_URL 为 SQLite 与 DEPLOY_MODE=distributed 冲突,按 DB_URL 使用本地 SQLite");
            }
            String normalized = withSqliteDefaults(url);
            if (!normalized.equals(url)) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(URL_KEY, normalized);
                environment.getPropertySources().addFirst(new MapPropertySource("sqliteDateParams", props));
            }
            return;
        }
        if (!url.startsWith("jdbc:mysql:")) {
            return;
        }

        String username = environment.getProperty(USERNAME_KEY);
        String password = environment.getProperty(PASSWORD_KEY);

        // 2) 显式 MySQL(或多实例部署):不允许降级,连不上直接失败并说明原因
        if (explicitMysql || distributed) {
            String source = explicitMysql ? "DB_TYPE=mysql" : "DEPLOY_MODE=distributed";
            if (explicitMysql && standalone) {
                log.warn("DB_TYPE=mysql 与 DEPLOY_MODE=standalone 冲突,按 DB_TYPE=mysql 强制使用 MySQL");
            }
            String reason = mysqlUnavailableReason(url, username, password);
            if (reason == null) {
                log.info("数据库:显式 MySQL 模式({}),已关闭 SQLite 回退", source);
                return;
            }
            throw new IllegalStateException(
                "显式 MySQL 模式(" + source + ")要求数据库可用,但连接失败: " + reason
                    + "。如需本地运行,请改设 DB_TYPE=sqlite 或 DEPLOY_MODE=standalone");
        }

        // 3) 自动模式:回退开关关闭时不探测
        Boolean fallbackEnabled = environment.getProperty(FALLBACK_ENABLED_KEY, Boolean.class, true);
        if (!Boolean.TRUE.equals(fallbackEnabled)) {
            return;
        }

        String reason = mysqlUnavailableReason(url, username, password);
        if (reason == null) {
            return;
        }
        String sqliteUrl = withSqliteDefaults(
            environment.getProperty(FALLBACK_URL_KEY, DEFAULT_SQLITE_URL));
        log.warn("MySQL 连接失败/未配置({}),自动回退到 SQLite: {}"
                + " (设 DEPLOY_MODE=standalone 显式使用本地库可跳过该探测,DB_FALLBACK_SQLITE=false 可关闭回退)",
            reason, sqliteUrl);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(URL_KEY, sqliteUrl);
        props.put(USERNAME_KEY, "");
        props.put(PASSWORD_KEY, "");
        environment.getPropertySources().addFirst(new MapPropertySource("sqliteFallback", props));
    }

    /** 归一化 {@code DB_TYPE};未配置或非法值返回 auto */
    private static String resolveDbType(ConfigurableEnvironment environment) {
        String raw = environment.getProperty(DB_TYPE_KEY);
        if (raw == null || raw.isBlank()) {
            return DB_TYPE_AUTO;
        }
        String type = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (DB_TYPE_SQLITE.equals(type) || DB_TYPE_MYSQL.equals(type) || DB_TYPE_AUTO.equals(type)) {
            return type;
        }
        log.warn("未知 DB_TYPE={},按 auto 处理(可选:sqlite/mysql/auto)", raw);
        return DB_TYPE_AUTO;
    }

    /**
     * 把数据源切换到本地 SQLite。
     *
     * <p>{@code DB_URL} 本身已是 SQLite 时沿用该地址,否则使用 {@code SQLITE_FALLBACK_URL}。</p>
     */
    private static void switchToSqlite(ConfigurableEnvironment environment, String currentUrl, String source) {
        boolean alreadySqlite = currentUrl != null && currentUrl.trim().startsWith("jdbc:sqlite:");
        String target = alreadySqlite
            ? currentUrl.trim()
            : environment.getProperty(FALLBACK_URL_KEY, DEFAULT_SQLITE_URL);
        String sqliteUrl = withSqliteDefaults(target);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(URL_KEY, sqliteUrl);
        props.put(USERNAME_KEY, "");
        props.put(PASSWORD_KEY, "");
        environment.getPropertySources().addFirst(new MapPropertySource("sqliteExplicit", props));
        log.info("数据库:显式 SQLite 模式({}),使用 {} (已跳过 MySQL 探测)", source, sqliteUrl);
    }

    /**
     * 给 SQLite 连接补齐日期参数与并发护栏参数,已显式设置的项保持不变。
     *
     * <p>逐项判断而不是「见到 date_class 就整体跳过」:{@code application.yml} 里的默认
     * SQLite 地址本身就带 date_class,整体跳过会让并发护栏永远补不上。</p>
     */
    static String withSqliteDefaults(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String result = url;
        if (!hasParam(result, "date_class") && !hasParam(result, "dateClass")) {
            result = appendParams(result, SQLITE_DATE_PARAMS);
        }
        for (String param : SQLITE_CONCURRENCY_PARAMS.split("&")) {
            String key = param.substring(0, param.indexOf('='));
            if (!hasParam(result, key)) {
                result = appendParams(result, param);
            }
        }
        return result;
    }

    /** URL 上是否已带该参数 */
    private static boolean hasParam(String url, String key) {
        return url.matches("(?i).*[?&]" + key + "=.*");
    }

    private static String appendParams(String url, String params) {
        return url.contains("?") ? url + "&" + params : url + "?" + params;
    }

    /**
     * 探测 MySQL 是否可用。
     *
     * @return 可用返回 {@code null},不可用返回失败原因(用于日志或启动失败提示)
     */
    private static String mysqlUnavailableReason(String url, String username, String password) {
        try {
            Class.forName(MYSQL_DRIVER);
        } catch (ClassNotFoundException e) {
            return "未找到 MySQL 驱动 " + MYSQL_DRIVER;
        }
        String probeUrl = withProbeTimeout(url);
        try (Connection ignored = DriverManager.getConnection(probeUrl, username, password)) {
            return null;
        } catch (SQLException e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }

    /** 给探测连接加上短超时,MySQL 不可达时不会长时间卡住启动 */
    private static String withProbeTimeout(String url) {
        if (url.matches("(?i).*[?&]connectTimeout=")) {
            return url;
        }
        String params = "connectTimeout=" + PROBE_TIMEOUT_MS + "&socketTimeout=" + PROBE_TIMEOUT_MS;
        return url.contains("?") ? url + "&" + params : url + "?" + params;
    }
}
