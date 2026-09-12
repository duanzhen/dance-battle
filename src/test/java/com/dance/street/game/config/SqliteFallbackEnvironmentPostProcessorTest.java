package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MySQL 不可达时数据源自动回退 SQLite,以及关闭开关/已是 SQLite 时不改动。
 * SQLite 连接会统一补齐 date_class=text 时间参数(与 README/建表脚本 TEXT 日期列一致)。
 * 探测地址用 127.0.0.1:1(连接必失败),避免依赖外部 MySQL。
 */
class SqliteFallbackEnvironmentPostProcessorTest {

    private final SqliteFallbackEnvironmentPostProcessor processor =
        new SqliteFallbackEnvironmentPostProcessor();

    @Test
    void appendsDateParamsToSqliteUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:sqlite:/tmp/game.db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/tmp/game.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
            env.getProperty("spring.datasource.url"));
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
            "jdbc:sqlite:/tmp/fallback.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void fallsBackToDefaultSqliteUrlWhenNotConfigured() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:1/game_db?useSSL=false&connectTimeout=1000");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:./data/game.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
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
            "jdbc:sqlite:/tmp/explicit.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
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
            "jdbc:sqlite:./data/game.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
            env.getProperty("spring.datasource.url"));
    }

    @Test
    void standaloneModeKeepsSqliteDbUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.deploy-mode", "standalone")
            .withProperty("spring.datasource.url", "jdbc:sqlite:/data/db/game.db");

        processor.postProcessEnvironment(env, null);

        assertEquals(
            "jdbc:sqlite:/data/db/game.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
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
            "jdbc:sqlite:./data/game.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
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
                "jdbc:sqlite:/tmp/native.db?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS",
                env.getProperty("spring.datasource.url"));
        });
    }
}
