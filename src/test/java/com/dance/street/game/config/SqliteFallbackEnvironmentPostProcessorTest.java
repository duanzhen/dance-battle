package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MySQL 不可达时数据源自动回退 SQLite,以及关闭开关/已是 SQLite 时不改动。
 * SQLite 连接会统一补齐日期参数(与 README/建表脚本 TEXT 日期列一致)与并发护栏参数
 * (WAL + busy_timeout + 事务 IMMEDIATE),详见 {@link SqliteFallbackEnvironmentPostProcessor}。
 * 探测地址用 127.0.0.1:1(连接必失败),避免依赖外部 MySQL。
 */
class SqliteFallbackEnvironmentPostProcessorTest {

    /** 日期参数之后的并发护栏参数(URL 上缺哪项补哪项) */
    private static final String CONCURRENCY =
        "&journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE&synchronous=NORMAL";
    private static final String DATE = "date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS";

    private final SqliteFallbackEnvironmentPostProcessor processor =
        new SqliteFallbackEnvironmentPostProcessor();

    @Test
    void appendsDateParamsToSqliteUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:sqlite:/tmp/game.db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/tmp/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    /**
     * 时间参数已存在时不能整体跳过:application.yml 的默认 SQLite 地址本身带 date_class,
     * 旧实现「见到 date_class 就 return」会让并发护栏永远补不上。
     */
    @Test
    void appendsConcurrencyParamsWhenDateParamsAlreadyPresent() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url",
                "jdbc:sqlite:./data/game.db?" + DATE);

        processor.postProcessEnvironment(env, null);

        assertEquals("jdbc:sqlite:./data/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    /** 用户显式写了的项不覆盖(例如自己设了 journal_mode / busy_timeout) */
    @Test
    void keepsExplicitlyConfiguredSqliteParams() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url",
                "jdbc:sqlite:./data/game.db?journal_mode=DELETE&busy_timeout=1000");

        processor.postProcessEnvironment(env, null);

        String url = env.getProperty("spring.datasource.url");
        assertTrue(url.contains("journal_mode=DELETE"), url);
        assertTrue(url.contains("busy_timeout=1000"), url);
        assertTrue(url.contains("transaction_mode=IMMEDIATE"), url);
        assertTrue(url.contains("synchronous=NORMAL"), url);
        assertTrue(url.contains("date_class=text"), url);
    }

    @Test
    void keepsMysqlUrlWhenFallbackDisabled() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.db.fallback-to-sqlite", "false")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db")
            .withProperty("spring.datasource.username", "root")
            .withProperty("spring.datasource.password", "");

        processor.postProcessEnvironment(env, null);

        assertEquals("jdbc:mysql://127.0.0.1:1/game_db", env.getProperty("spring.datasource.url"));
    }

    @Test
    void fallsBackToConfiguredSqliteUrlWhenMysqlUnreachable() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.db.sqlite-fallback-url", "jdbc:sqlite:/tmp/fallback.db")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db?useSSL=false&connectTimeout=1000")
            .withProperty("spring.datasource.username", "root")
            .withProperty("spring.datasource.password", "");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/tmp/fallback.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void fallsBackToDefaultSqliteUrlWhenNotConfigured() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db?useSSL=false&connectTimeout=1000");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:./data/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void dbTypeSqliteSkipsMysqlProbeAndUsesConfiguredSqliteUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.db.type", "sqlite")
            .withProperty("app.db.sqlite-fallback-url", "jdbc:sqlite:/tmp/explicit.db")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db")
            .withProperty("spring.datasource.username", "root")
            .withProperty("spring.datasource.password", "root");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/tmp/explicit.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
        assertEquals("", env.getProperty("spring.datasource.username"));
        assertEquals("", env.getProperty("spring.datasource.password"));
    }

    @Test
    void standaloneModeForcesSqliteWithoutProbe() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.deploy-mode", "standalone")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:./data/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void standaloneModeKeepsSqliteDbUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.deploy-mode", "standalone")
            .withProperty("spring.datasource.url", "jdbc:sqlite:/data/db/game.db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/data/db/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void dbTypeMysqlDisablesFallbackAndFailsFast() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.db.type", "mysql")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db")
            .withProperty("spring.datasource.username", "root")
            .withProperty("spring.datasource.password", "root");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> processor.postProcessEnvironment(env, null));

        assertTrue(error.getMessage().contains("DB_TYPE=mysql"));
        assertEquals("jdbc:mysql://127.0.0.1:1/game_db", env.getProperty("spring.datasource.url"));
    }

    @Test
    void autoModeStillFallsBackWhenMysqlUnreachable() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.deploy-mode", "auto")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:./data/game.db?" + DATE + CONCURRENCY,
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void nativeImageDefaultsToSqliteWithoutProbe() {
        NativeImageFlag.runAsNativeImage(() -> {
            MockEnvironment env = new MockEnvironment()
                .withProperty("app.db.type", "auto")
                .withProperty("app.deploy-mode", "")
                .withProperty("app.db.sqlite-fallback-url", "jdbc:sqlite:/tmp/native.db")
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db");

            processor.postProcessEnvironment(env, null);

            assertEquals(
                "jdbc:sqlite:/tmp/native.db?" + DATE + CONCURRENCY,
                env.getProperty("spring.datasource.url"));
        });
    }
}
