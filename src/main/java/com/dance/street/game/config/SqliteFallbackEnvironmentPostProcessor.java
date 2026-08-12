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
 * MySQL 未配置/不可达时的 SQLite 自动回退。
 *
 * <p>应用启动时若数据源为 MySQL,先用短超时探测一次连通性;连接失败
 * (MySQL 未启动、主机不可达、未装驱动等)则把 {@code spring.datasource.*}
 * 切换为 SQLite 文件库,后续 Hikari、MyBatis-Plus 方言、schema 自检都会
 * 自动按 SQLite 走,无需改任何业务代码。</p>
 *
 * <p>可通过环境变量关闭:{@code DB_FALLBACK_SQLITE=false};回退地址可用
 * {@code SQLITE_FALLBACK_URL} 覆盖(默认 {@code jdbc:sqlite:./data/game.db})。
 * SQLite 连接会统一带上 {@code date_class=text} 参数,与建表脚本中的 TEXT 日期列一致,
 * 避免日期字段按整数写入后无法解析。</p>
 */
public class SqliteFallbackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqliteFallbackEnvironmentPostProcessor.class);

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL_KEY = "spring.datasource.url";
    private static final String USERNAME_KEY = "spring.datasource.username";
    private static final String PASSWORD_KEY = "spring.datasource.password";
    private static final String FALLBACK_ENABLED_KEY = "app.db.fallback-to-sqlite";
    private static final String FALLBACK_URL_KEY = "app.db.sqlite-fallback-url";
    private static final String DEFAULT_SQLITE_URL = "jdbc:sqlite:./data/game.db";
    /** 与 sql/game_db.sqlite.sql 中 TEXT 日期列匹配的 sqlite-jdbc 日期参数 */
    private static final String SQLITE_DATE_PARAMS = "date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS";
    private static final int PROBE_TIMEOUT_MS = 3000;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Boolean enabled = environment.getProperty(FALLBACK_ENABLED_KEY, Boolean.class, true);
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        String url = environment.getProperty(URL_KEY);
        if (url == null || url.isBlank()) {
            return;
        }
        // 直接使用 SQLite(DB_URL=jdbc:sqlite:...):补齐日期参数,保证时间字段读写一致
        if (url.startsWith("jdbc:sqlite:")) {
            String normalized = withSqliteDateParams(url);
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
        if (mysqlReachable(url, username, password)) {
            return;
        }

        String sqliteUrl = withSqliteDateParams(environment.getProperty(FALLBACK_URL_KEY, DEFAULT_SQLITE_URL));
        log.warn("MySQL 连接失败/未配置,自动回退到 SQLite: {} (设 DB_FALLBACK_SQLITE=false 可关闭)",
            sqliteUrl);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(URL_KEY, sqliteUrl);
        props.put(USERNAME_KEY, "");
        props.put(PASSWORD_KEY, "");
        environment.getPropertySources().addFirst(new MapPropertySource("sqliteFallback", props));
    }

    /** 给 SQLite 连接补齐日期参数;用户已显式设置 date_class 时保持不变 */
    static String withSqliteDateParams(String url) {
        if (url == null || url.matches("(?i).*[?&](date_class|dateClass)=.*")) {
            return url;
        }
        return url.contains("?") ? url + "&" + SQLITE_DATE_PARAMS : url + "?" + SQLITE_DATE_PARAMS;
    }

    /** 探测 MySQL:驱动缺失或连接失败均视为不可用,返回 false 触发回退 */
    private static boolean mysqlReachable(String url, String username, String password) {
        try {
            Class.forName(MYSQL_DRIVER);
        } catch (ClassNotFoundException e) {
            log.warn("未找到 MySQL 驱动({}),自动回退到 SQLite", MYSQL_DRIVER);
            return false;
        }
        String probeUrl = withProbeTimeout(url);
        try (Connection ignored = DriverManager.getConnection(probeUrl, username, password)) {
            return true;
        } catch (SQLException e) {
            log.warn("MySQL 探测失败({}),自动回退到 SQLite", e.getMessage());
            return false;
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
