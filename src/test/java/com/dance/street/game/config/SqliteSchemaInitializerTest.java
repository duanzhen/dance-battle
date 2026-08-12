package com.dance.street.game.config;

import com.dance.street.game.service.LoginAccountService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 支持验证:在内存库上真实运行 schema 自检建表,
 * 并验证登录账号的首次初始化/修改密码走跨库 JDBC 逻辑。
 */
class SqliteSchemaInitializerTest {

    private DataSource newSqliteDataSource() {
        // suppressClose=true:内存库必须复用同一条连接,否则每次 getConnection 都是空库
        return new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    }

    @Test
    void schemaInitializerCreatesAllTablesAndIndexesOnSqlite() throws Exception {
        DataSource dataSource = newSqliteDataSource();
        DatabaseSchemaInitializer initializer = new DatabaseSchemaInitializer(
            dataSource, "jdbc:sqlite::memory:", null, null, true);

        initializer.afterSingletonsInstantiated();

        List<String> tables = queryStrings(dataSource,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
        assertEquals(14, tables.size());
        assertTrue(tables.contains("t_tournament"));
        assertTrue(tables.contains("t_vis_widget"));
        assertTrue(tables.contains("t_login_account"));

        List<String> indexes = queryStrings(dataSource,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name");
        assertTrue(indexes.contains("uk_username"));
        assertTrue(indexes.contains("idx_match_score"));
        assertTrue(indexes.contains("uk_referee_stage"));

        // 重复执行应幂等,不抛异常
        initializer.afterSingletonsInstantiated();
    }

    @Test
    void loginAccountServiceWorksOnSqlite() {
        DataSource dataSource = newSqliteDataSource();
        new DatabaseSchemaInitializer(dataSource, "jdbc:sqlite::memory:", null, null, true)
            .afterSingletonsInstantiated();

        LoginAccountService service = new LoginAccountService(new JdbcTemplate(dataSource));
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "123456");

        // 首次校验:表中无记录时回退配置默认值并落库
        assertTrue(service.verify("admin", "123456"));

        // 修改密码后旧密码失效、新密码生效
        service.changePassword("123456", "new-pass-123");
        assertFalse(service.verify("admin", "123456"));
        assertTrue(service.verify("admin", "new-pass-123"));

        // 旧密码错误 / 新旧密码相同时报业务异常
        assertThrows(ServiceException.class,
            () -> service.changePassword("wrong-old", "another-pass-123"));
        assertThrows(ServiceException.class,
            () -> service.changePassword("new-pass-123", "new-pass-123"));
    }

    @Test
    void defaultPasswordForceChangeFlow() {
        DataSource dataSource = newSqliteDataSource();
        new DatabaseSchemaInitializer(dataSource, "jdbc:sqlite::memory:", null, null, true)
            .afterSingletonsInstantiated();

        LoginAccountService service = new LoginAccountService(new JdbcTemplate(dataSource));
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "123456");
        // 未显式传入 LOGIN_PASSWORD 环境变量
        ReflectionTestUtils.setField(service, "environment", new MockEnvironment());

        // 首次登录(使用内置默认密码)判定为默认密码
        assertTrue(service.verify("admin", "123456"));
        assertTrue(service.isDefaultPassword());

        // 默认密码状态下无需旧密码即可修改,但不允许继续使用默认密码
        assertThrows(ServiceException.class, () -> service.changePassword(null, "123456"));
        service.changePassword(null, "new-pass-123");
        assertFalse(service.isDefaultPassword());
        assertTrue(service.verify("admin", "new-pass-123"));

        // 修改后不再处于默认状态,必须校验旧密码
        assertThrows(ServiceException.class, () -> service.changePassword(null, "another-pass-123"));
    }

    @Test
    void envProvidedPasswordIsNotDefault() {
        DataSource dataSource = newSqliteDataSource();
        new DatabaseSchemaInitializer(dataSource, "jdbc:sqlite::memory:", null, null, true)
            .afterSingletonsInstantiated();

        LoginAccountService service = new LoginAccountService(new JdbcTemplate(dataSource));
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "123456");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("LOGIN_PASSWORD", "my-custom-pass");
        ReflectionTestUtils.setField(service, "environment", environment);

        assertTrue(service.verify("admin", "123456"));
        // 显式通过环境变量传入的密码不算默认密码,不触发强制改密
        assertFalse(service.isDefaultPassword());
    }

    @Test
    void envEqualToBuiltinDefaultIsStillDefault() {
        DataSource dataSource = newSqliteDataSource();
        new DatabaseSchemaInitializer(dataSource, "jdbc:sqlite::memory:", null, null, true)
            .afterSingletonsInstantiated();

        LoginAccountService service = new LoginAccountService(new JdbcTemplate(dataSource));
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "123456");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("LOGIN_PASSWORD", "123456");
        ReflectionTestUtils.setField(service, "environment", environment);

        assertTrue(service.verify("admin", "123456"));
        // 环境变量值等于内置默认值 → 仍视为默认密码,首次登录强制修改
        assertTrue(service.isDefaultPassword());

        // 默认态下无需旧密码即可修改,且不允许新密码继续使用默认值
        assertThrows(ServiceException.class, () -> service.changePassword(null, "123456"));
        service.changePassword(null, "new-pass-123");
        assertFalse(service.isDefaultPassword());
    }

    private static List<String> queryStrings(DataSource dataSource, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
