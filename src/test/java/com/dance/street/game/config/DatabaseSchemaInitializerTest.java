package com.dance.street.game.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 sql/game_db.sql 的建表语句解析:只保留 CREATE TABLE,
 * 忽略 DROP/SET/注释,并正确识别 16 张业务表。
 */
class DatabaseSchemaInitializerTest {

    @Test
    void parseScriptOnlyKeepsCreateTableStatements() throws IOException {
        String script = Files.readString(Path.of("sql/game_db.sql"), StandardCharsets.UTF_8);

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);

        assertEquals(16, ddlList.size());
        for (String ddl : ddlList) {
            assertTrue(ddl.matches("(?is)^CREATE\\s+TABLE.*"), "应为 CREATE TABLE 语句: " + ddl);
            assertFalse(ddl.matches("(?is)^DROP\\s+TABLE.*"), "不应包含 DROP 语句: " + ddl);
        }
    }

    @Test
    void parseColumnDefinitionsSkipsTableConstraints() {
        String ddl = "CREATE TABLE `t_x` (\n"
            + "  `id` bigint NOT NULL,\n"
            + "  `amount` decimal(10,2) DEFAULT NULL,\n"
            + "  `remark` varchar(50) DEFAULT NULL COMMENT '标签A,标签B',\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  KEY `idx_amount` (`amount`),\n"
            + "  CONSTRAINT `fk_x` FOREIGN KEY (`id`) REFERENCES `t_y` (`id`)\n"
            + ") ENGINE=InnoDB COMMENT='尾注(带括号,逗号)'";

        java.util.Map<String, String> columns = DatabaseSchemaInitializer.parseColumnDefinitions(ddl);

        // 类型里的逗号 decimal(10,2)、注释里的逗号都不应把定义切断;表级约束不算列
        assertEquals(java.util.Set.of("id", "amount", "remark"), columns.keySet());
        assertEquals("`amount` decimal(10,2) DEFAULT NULL", columns.get("amount"));
    }

    @Test
    void extractTableNameHandlesBacktickQuotedNames() {
        String ddl = "CREATE TABLE `t_tournament` (\n"
            + "  `id` bigint NOT NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB COMMENT='赛事主表'";

        assertEquals("t_tournament", DatabaseSchemaInitializer.extractTableName(ddl));
    }

    @Test
    void withIfNotExistsAddsGuardAndKeepsExistingGuard() {
        String plain = "CREATE TABLE `t_match` (`id` bigint NOT NULL) ENGINE=InnoDB";
        String guarded = DatabaseSchemaInitializer.withIfNotExists(plain);
        assertTrue(guarded.startsWith("CREATE TABLE IF NOT EXISTS `t_match`"));

        String already = "CREATE TABLE IF NOT EXISTS `t_match` (`id` bigint NOT NULL)";
        assertEquals(already, DatabaseSchemaInitializer.withIfNotExists(already));
    }

    @Test
    void sqliteScriptParsesTablesAndIndexes() throws IOException {
        String script = Files.readString(Path.of("sql/game_db.sqlite.sql"), StandardCharsets.UTF_8);

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);
        List<String> indexList = DatabaseSchemaInitializer.parseCreateIndexStatements(script);

        assertEquals(16, ddlList.size());
        assertEquals(18, indexList.size());
        for (String index : indexList) {
            assertTrue(index.matches("(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX.*"), "应为 CREATE INDEX 语句: " + index);
            assertFalse(index.matches("(?is)^CREATE\\s+TABLE.*"), "不应包含建表语句: " + index);
        }
    }

    @Test
    void withIfNotExistsIndexAddsGuardAndKeepsExistingGuard() {
        String plain = "CREATE INDEX `idx_comp` ON `t_competitor_member` (`competitor_id`)";
        assertEquals(
            "CREATE INDEX IF NOT EXISTS `idx_comp` ON `t_competitor_member` (`competitor_id`)",
            DatabaseSchemaInitializer.withIfNotExistsIndex(plain));

        String unique = "CREATE UNIQUE INDEX `uk_username` ON `t_login_account` (`username`)";
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS `uk_username` ON `t_login_account` (`username`)",
            DatabaseSchemaInitializer.withIfNotExistsIndex(unique));

        String already = "CREATE UNIQUE INDEX IF NOT EXISTS `uk_username` ON `t_login_account` (`username`)";
        assertEquals(already, DatabaseSchemaInitializer.withIfNotExistsIndex(already));
    }
}
