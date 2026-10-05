package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 sql/game_db.sql 的建表语句解析:只保留 CREATE TABLE,
 * 忽略 DROP/SET/注释,并正确识别 17 张业务表。
 */
class DatabaseSchemaInitializerTest {

    @Test
    void parseScriptOnlyKeepsCreateTableStatements() throws IOException {
        String script = Files.readString(Path.of("sql/game_db.sql"), StandardCharsets.UTF_8);

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);

        assertEquals(17, ddlList.size());
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
            + "  -- 夹在列定义之间的注释行(不能当成列名)\n"
            + "  `flagged` tinyint NOT NULL DEFAULT 0,\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  KEY `idx_amount` (`amount`),\n"
            + "  CONSTRAINT `fk_x` FOREIGN KEY (`id`) REFERENCES `t_y` (`id`)\n"
            + ") ENGINE=InnoDB COMMENT='尾注(带括号,逗号)'";

        java.util.Map<String, String> columns = DatabaseSchemaInitializer.parseColumnDefinitions(ddl);

        // 类型里的逗号 decimal(10,2)、注释里的逗号都不应把定义切断;表级约束与行内注释不算列
        assertEquals(java.util.Set.of("id", "amount", "remark", "flagged"), columns.keySet());
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

    /** 注释里的分号不能把 CREATE TABLE 截断(否则该表后面的列永远补不出来)。 */
    @Test
    void statementSplitIgnoresSemicolonsInsideQuotes() {
        String script = "CREATE TABLE `t_x` (\n"
            + "  `id` bigint NOT NULL,\n"
            + "  `a` bigint DEFAULT NULL COMMENT '甲;乙',\n"
            + "  `b` varchar(10) NOT NULL DEFAULT 'P;Q'\n"
            + ") ENGINE=InnoDB;\n"
            + "CREATE INDEX `idx_x` ON `t_x` (`id`);\n";

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);
        assertEquals(1, ddlList.size());
        java.util.Map<String, String> cols = DatabaseSchemaInitializer.parseColumnDefinitions(ddlList.get(0));
        assertEquals(java.util.Set.of("id", "a", "b"), cols.keySet());
        assertEquals(1, DatabaseSchemaInitializer.parseCreateIndexStatements(script).size());
    }

    @Test
    void sqliteScriptParsesTablesAndIndexes() throws IOException {
        String script = Files.readString(Path.of("sql/game_db.sqlite.sql"), StandardCharsets.UTF_8);

        List<String> ddlList = DatabaseSchemaInitializer.parseCreateTableStatements(script);
        List<String> indexList = DatabaseSchemaInitializer.parseCreateIndexStatements(script);

        assertEquals(17, ddlList.size());
        assertEquals(22, indexList.size());
        for (String index : indexList) {
            assertTrue(index.matches("(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX.*"), "应为 CREATE INDEX 语句: " + index);
            assertFalse(index.matches("(?is)^CREATE\\s+TABLE.*"), "不应包含建表语句: " + index);
        }

        // 老库缺列靠 parseColumnDefinitions 出来的定义 ALTER 补齐:确认软删除列能被解析到
        java.util.Map<String, String> tournamentColumns = ddlList.stream()
            .filter(ddl -> "t_tournament".equals(DatabaseSchemaInitializer.extractTableName(ddl)))
            .findFirst()
            .map(DatabaseSchemaInitializer::parseColumnDefinitions)
            .orElseThrow();
        assertTrue(tournamentColumns.containsKey("deleted"), "t_tournament 应解析出 deleted 列(供老库自动补列)");
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

    /**
     * 老 SQLite 库里 {@code t_match_participant.competitor_id} 是 NOT NULL 且没有 slot_kind;
     * 启动自检必须放宽可空性(重建表)、补齐 slot_kind,并保留既有数据。
     */
    @Test
    void sqliteMigrationRelaxesParticipantCompetitorAndAddsSlotKind() throws Exception {
        Path db = Path.of("target/schema-migrate-participant.db");
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(db + suffix));
        }
        String url = "jdbc:sqlite:" + db;
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE `t_match_participant` ("
                + "`id` INTEGER NOT NULL, `tenant_id` INTEGER NOT NULL, `tournament_id` INTEGER NOT NULL,"
                + "`match_id` INTEGER NOT NULL, `competitor_id` INTEGER NOT NULL, `display_slot_index` INTEGER,"
                + "`score_value` NUMERIC, `rank_in_match` INTEGER, `outcome_status` TEXT, `create_by` INTEGER,"
                + "`create_time` TEXT, `update_by` INTEGER, `update_time` TEXT, `remark` TEXT, PRIMARY KEY (`id`))");
            st.execute("INSERT INTO `t_match_participant`"
                + " (id, tenant_id, tournament_id, match_id, competitor_id, display_slot_index)"
                + " VALUES (1, 0, 0, 0, 42, 0)");
        }

        new DatabaseSchemaInitializer(new DriverManagerDataSource(url), url, "", "", true)
            .afterSingletonsInstantiated();

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            boolean notNull = true;
            boolean hasSlotKind = false;
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(`t_match_participant`)")) {
                while (rs.next()) {
                    if ("competitor_id".equalsIgnoreCase(rs.getString("name"))) {
                        notNull = rs.getInt("notnull") == 1;
                    }
                    if ("slot_kind".equalsIgnoreCase(rs.getString("name"))) {
                        hasSlotKind = true;
                    }
                }
            }
            assertFalse(notNull, "competitor_id 应放宽为可空(支持轮空/待定占位行)");
            assertTrue(hasSlotKind, "应补齐 slot_kind 列");

            try (ResultSet rs = st.executeQuery(
                "SELECT competitor_id, slot_kind FROM `t_match_participant` WHERE id = 1")) {
                assertTrue(rs.next(), "既有行应被保留");
                assertEquals(42L, rs.getLong("competitor_id"));
                assertEquals("PLAYER", rs.getString("slot_kind"), "既有行应沿用默认座位类型");
            }
            // 放宽后可插入 NULL 座位(轮空)
            st.execute("INSERT INTO `t_match_participant`"
                + " (id, tenant_id, tournament_id, match_id, competitor_id, display_slot_index, slot_kind)"
                + " VALUES (2, 0, 0, 0, NULL, 1, 'BYE')");
        }
    }
}
