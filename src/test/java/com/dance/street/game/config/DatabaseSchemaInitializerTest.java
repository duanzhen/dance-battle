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
 * 忽略 DROP/SET/注释,并正确识别 13 张业务表。
 */
class DatabaseSchemaInitializerTest {

    @Test
    void parseScriptOnlyKeepsCreateTableStatements() throws IOException {
        String script = Files.readString(Path.of("sql/game_db.sql"), StandardCharsets.UTF_8);

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);

        assertEquals(13, ddlList.size());
        for (String ddl : ddlList) {
            assertTrue(ddl.matches("(?is)^CREATE\\s+TABLE.*"), "应为 CREATE TABLE 语句: " + ddl);
            assertFalse(ddl.matches("(?is)^DROP\\s+TABLE.*"), "不应包含 DROP 语句: " + ddl);
        }
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
}
