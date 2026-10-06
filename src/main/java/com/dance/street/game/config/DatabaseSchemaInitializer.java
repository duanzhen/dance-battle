package com.dance.street.game.config;

import cn.hutool.core.util.IdUtil;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库 schema 启动自检 + JDBC 兜底初始化。
 *
 * <p>应用启动时检查数据库与业务表是否存在,缺失时读取建表脚本自动补齐
 * (MySQL 用 {@code sql/game_db.sql},SQLite 用 {@code sql/game_db.sqlite.sql}),
 * 不依赖容器初始化脚本、也不依赖 Spring SQL Init,
 * 因此无论是 Docker Compose、裸 jar、直连已有 MySQL 实例还是 SQLite 文件,表结构都能自动就绪。</p>
 *
 * <p>兜底逻辑全部基于原生 JDBC:</p>
 * <ol>
 *   <li>先尝试用主数据源连接;若报“数据库不存在”,用去掉库名的 URL 连接实例,
 *       执行 {@code CREATE DATABASE IF NOT EXISTS} 建库;</li>
 *   <li>逐表查询元数据判断是否存在(MySQL 用 {@code information_schema},
 *       SQLite 用 {@code sqlite_master}),缺失的表以
 *       {@code CREATE TABLE IF NOT EXISTS} 创建(保留已有表与数据,绝不执行 DROP);</li>
 *   <li>表已存在但缺列时(旧版本建的表 + 新版本新增字段),按脚本里的列定义
 *       {@code ALTER TABLE ... ADD COLUMN} 补齐——同样只加列,不删不改已有列与数据;</li>
 *   <li>SQLite 额外执行脚本中的 {@code CREATE INDEX IF NOT EXISTS}(MySQL 索引内联在表定义中);</li>
 *   <li>可开关: {@code app.schema-init.enabled=false} 或环境变量
 *       {@code SCHEMA_INIT_ENABLED=false} 关闭。</li>
 * </ol>
 *
 * @author duane
 */
@Slf4j
@Component
public class DatabaseSchemaInitializer implements SmartInitializingSingleton {

    /** 解析 CREATE TABLE 语句及表名(兼容行首注释、带/不带反引号、IF NOT EXISTS) */
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([\\w$]+)`?");
    private static final Pattern CREATE_TABLE_IF_NOT_EXISTS_PATTERN = Pattern.compile(
        "(?im)^\\s*CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS");
    /** 解析 CREATE [UNIQUE] INDEX 语句(SQLite 独立索引) */
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile(
        "(?im)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([\\w$]+)`?");
    private static final Pattern CREATE_INDEX_IF_NOT_EXISTS_PATTERN = Pattern.compile(
        "(?im)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS");

    private static final String SQL_RESOURCE_CLASSPATH = "sql/game_db.sql";
    private static final String SQL_RESOURCE_FILESYSTEM = "sql/game_db.sql";
    private static final String SQLITE_SQL_RESOURCE_CLASSPATH = "sql/game_db.sqlite.sql";
    private static final String SQLITE_SQL_RESOURCE_FILESYSTEM = "sql/game_db.sqlite.sql";
    /** 需要额外放宽 competitor_id 可空性(轮空/待定占位行)的表 */
    private static final String PARTICIPANT_TABLE = "t_match_participant";
    /** 需要额外放宽 slot 可空性(多入口汇合时"待落位"行没有座位号)的表 */
    private static final String ROSTER_ENTRY_TABLE = "t_stage_roster_entry";
    /** 名单来源组(赛段间依赖的边+取人规则)表:老库的 JSON 规则要一次性搬进来 */
    private static final String ROSTER_GROUP_TABLE = "t_stage_roster_group";

    private final DataSource dataSource;
    private final String jdbcUrl;
    private final String jdbcUsername;
    private final String jdbcPassword;
    private final boolean enabled;
    /** 是否 SQLite 数据源(由 JDBC URL 自动识别) */
    private final boolean sqlite;

    public DatabaseSchemaInitializer(
        DataSource dataSource,
        @Value("${spring.datasource.url:}") String jdbcUrl,
        @Value("${spring.datasource.username:}") String jdbcUsername,
        @Value("${spring.datasource.password:}") String jdbcPassword,
        @Value("${app.schema-init.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUsername = jdbcUsername;
        this.jdbcPassword = jdbcPassword;
        this.enabled = enabled;
        this.sqlite = jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:");
    }

    /**
     * 全部单例 Bean 实例化完成后执行(此时 DataSource/MyBatis 已就绪,Web 服务尚未对外),
     * 保证业务请求到达前表结构已就绪。
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            log.info("数据库 schema 自动初始化已关闭(app.schema-init.enabled=false)");
            return;
        }
        if (sqlite) {
            initSqliteSchema();
            return;
        }
        initMysqlSchema();
    }

    /** MySQL 建库建表:连接主库失败且为“库不存在”时自动 CREATE DATABASE,再逐表补齐 */
    private void initMysqlSchema() {
        String script = loadSqlScript(SQL_RESOURCE_CLASSPATH, SQL_RESOURCE_FILESYSTEM);
        List<String> ddlList = parseCreateTableStatements(script);
        if (ddlList.isEmpty()) {
            log.warn("sql/game_db.sql 中未解析到任何 CREATE TABLE 语句,跳过 schema 初始化");
            return;
        }

        ensureDatabase();

        int created = 0;
        int existed = 0;
        int failed = 0;
        int added = 0;
        try (Connection connection = dataSource.getConnection()) {
            for (String ddl : ddlList) {
                String table = extractTableName(ddl);
                try {
                    if (tableExists(connection, table)) {
                        existed++;
                        log.debug("数据表已存在,跳过建表: {}", table);
                        added += addMissingColumns(connection, table, ddl, false);
                        if (PARTICIPANT_TABLE.equals(table)) {
                            relaxParticipantCompetitorNullable(connection, false, ddl);
                        }
                        if (ROSTER_ENTRY_TABLE.equals(table)) {
                            relaxRosterEntrySlotNullable(connection, false, ddl);
                        }
                    } else {
                        executeDdl(connection, withIfNotExists(ddl));
                        created++;
                        log.info("自动建表成功: {}", table);
                    }
                } catch (SQLException e) {
                    failed++;
                    log.error("自动建表失败: {} - {}", table, e.getMessage());
                }
            }
            migrateRosterGroups(connection, false);
            collapsePerCompetitorRounds(connection);
        } catch (SQLException e) {
            log.error("连接数据库检查表结构失败: {}", e.getMessage());
            return;
        }
        if (failed > 0) {
            log.warn("schema 自检完成: 已存在 {} 张,新建 {} 张,失败 {} 张", existed, created, failed);
        } else {
            log.info("schema 自检完成: 已存在 {} 张,新建 {} 张", existed, created);
        }
        logAddedColumns(added);
    }

    /** SQLite 建表:确保数据文件目录存在,再逐表/逐索引补齐(文件数据库无需建库) */
    private void initSqliteSchema() {
        String script = loadSqlScript(SQLITE_SQL_RESOURCE_CLASSPATH, SQLITE_SQL_RESOURCE_FILESYSTEM);
        List<String> ddlList = parseCreateTableStatements(script);
        List<String> indexList = parseCreateIndexStatements(script);
        if (ddlList.isEmpty()) {
            log.warn("sql/game_db.sqlite.sql 中未解析到任何 CREATE TABLE 语句,跳过 schema 初始化");
            return;
        }

        ensureSqliteParentDirectory();

        int created = 0;
        int existed = 0;
        int failed = 0;
        int added = 0;
        try (Connection connection = dataSource.getConnection()) {
            for (String ddl : ddlList) {
                String table = extractTableName(ddl);
                try {
                    if (sqliteTableExists(connection, table)) {
                        existed++;
                        log.debug("数据表已存在,跳过建表: {}", table);
                        added += addMissingColumns(connection, table, ddl, true);
                        if (PARTICIPANT_TABLE.equals(table)) {
                            relaxParticipantCompetitorNullable(connection, true, ddl);
                        }
                        if (ROSTER_ENTRY_TABLE.equals(table)) {
                            relaxRosterEntrySlotNullable(connection, true, ddl);
                        }
                    } else {
                        executeDdl(connection, withIfNotExists(ddl));
                        created++;
                        log.info("自动建表成功: {}", table);
                    }
                } catch (SQLException e) {
                    failed++;
                    log.error("自动建表失败: {} - {}", table, e.getMessage());
                }
            }
            for (String index : indexList) {
                try {
                    executeDdl(connection, withIfNotExistsIndex(index));
                } catch (SQLException e) {
                    failed++;
                    log.error("自动建索引失败: {} - {}", extractIndexName(index), e.getMessage());
                }
            }
            migrateRosterGroups(connection, true);
            collapsePerCompetitorRounds(connection);
        } catch (SQLException e) {
            log.error("连接 SQLite 检查表结构失败: {}", e.getMessage());
            return;
        }
        if (failed > 0) {
            log.warn("schema 自检完成: 已存在 {} 张,新建 {} 张,失败 {} 项", existed, created, failed);
        } else {
            log.info("schema 自检完成: 已存在 {} 张,新建 {} 张", existed, created);
        }
        logAddedColumns(added);
    }

    private static void logAddedColumns(int added) {
        if (added > 0) {
            log.info("schema 自检: 为已有表补齐 {} 个缺失列", added);
        }
    }

    /**
     * 确保数据库存在:主数据源能连上说明库已存在;连不上且报“未知数据库”时,
     * 用去掉库名的 URL 连接 MySQL 实例并自动建库。
     */
    private void ensureDatabase() {
        try (Connection ignored = dataSource.getConnection()) {
            return;
        } catch (SQLException e) {
            if (!isUnknownDatabase(e)) {
                log.warn("连接数据库失败(非库缺失),跳过建库: {}", e.getMessage());
                return;
            }
        }

        String serverUrl = stripDatabaseName(jdbcUrl);
        String database = extractDatabaseName(jdbcUrl);
        if (serverUrl == null || database.isEmpty()) {
            log.error("无法解析数据库 URL({}),跳过自动建库", jdbcUrl);
            return;
        }
        String createDb = "CREATE DATABASE IF NOT EXISTS `" + database
            + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci";
        try (Connection connection = DriverManager.getConnection(serverUrl, jdbcUsername, jdbcPassword);
             Statement statement = connection.createStatement()) {
            statement.execute(createDb);
            log.info("数据库 {} 不存在,已通过 JDBC 自动创建", database);
        } catch (SQLException e) {
            log.error("自动建库失败({}): {},请手动创建数据库后重试", database, e.getMessage());
        }
    }

    /**
     * 判断异常是否为“数据库不存在”(mysql-connector-j 常见于错误码 1049 或
     * “Unknown database”字样)。
     */
    private static boolean isUnknownDatabase(SQLException e) {
        return e.getErrorCode() == 1049
            || (e.getMessage() != null && e.getMessage().toLowerCase().contains("unknown database"));
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        String schema = connection.getCatalog();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** SQLite 表存在性检查:查询 sqlite_master */
    private static boolean sqliteTableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 老库的 {@code t_match_participant.competitor_id} 是 NOT NULL;轮空/待定座位需要落 NULL 占位行,
     * 这里做一次性放宽(只放宽可空性,不动数据):
     * MySQL 用 {@code MODIFY COLUMN};SQLite 不支持改列,按重建表的方式搬迁。
     */
    private static void relaxParticipantCompetitorNullable(Connection connection, boolean sqlite, String ddl)
        throws SQLException {
        if (sqlite) {
            Boolean notNull = null;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA table_info(`" + PARTICIPANT_TABLE + "`)")) {
                while (rs.next()) {
                    if ("competitor_id".equalsIgnoreCase(rs.getString("name"))) {
                        notNull = rs.getInt("notnull") == 1;
                        break;
                    }
                }
            }
            if (notNull == null || !notNull) {
                return;
            }
            rebuildSqliteParticipant(connection, ddl);
            log.info("schema 自检: 已放宽 {}.competitor_id 为可空(支持轮空/待定占位行)", PARTICIPANT_TABLE);
            return;
        }
        String nullable = null;
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = 'competitor_id'")) {
            ps.setString(1, connection.getCatalog());
            ps.setString(2, PARTICIPANT_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nullable = rs.getString(1);
                }
            }
        }
        if (nullable == null || "YES".equalsIgnoreCase(nullable)) {
            return;
        }
        executeDdl(connection, "ALTER TABLE `" + PARTICIPANT_TABLE
            + "` MODIFY COLUMN `competitor_id` bigint NULL COMMENT '参赛方ID;轮空/待定占位行为 NULL'");
        log.info("schema 自检: 已放宽 {}.competitor_id 为可空(支持轮空/待定占位行)", PARTICIPANT_TABLE);
    }

    /** SQLite 不支持 ALTER 去掉 NOT NULL:按「建新表 → 拷数据 → 删旧表 → 改名」搬迁,索引随后由脚本重建 */
    private static void rebuildSqliteParticipant(Connection connection, String createDdl) throws SQLException {
        rebuildSqliteTable(connection, PARTICIPANT_TABLE, createDdl, "__slot_rebuild");
    }

    /**
     * 老库的 {@code t_stage_roster_entry.slot} 是 NOT NULL;多入口汇合时"待落位"行没有座位号,
     * 这里做一次性放宽(只放宽可空性,不动数据):MySQL 用 {@code MODIFY COLUMN},SQLite 重建表搬迁。
     */
    private static void relaxRosterEntrySlotNullable(Connection connection, boolean sqlite, String ddl)
        throws SQLException {
        if (sqlite) {
            Boolean notNull = null;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA table_info(`" + ROSTER_ENTRY_TABLE + "`)")) {
                while (rs.next()) {
                    if ("slot".equalsIgnoreCase(rs.getString("name"))) {
                        notNull = rs.getInt("notnull") == 1;
                        break;
                    }
                }
            }
            if (notNull == null || !notNull) {
                return;
            }
            rebuildSqliteTable(connection, ROSTER_ENTRY_TABLE, ddl, "__holding_rebuild");
            log.info("schema 自检: 已放宽 {}.slot 为可空(支持多入口汇合的待落位行)", ROSTER_ENTRY_TABLE);
            return;
        }
        String nullable = null;
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = 'slot'")) {
            ps.setString(1, connection.getCatalog());
            ps.setString(2, ROSTER_ENTRY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nullable = rs.getString(1);
                }
            }
        }
        if (nullable == null || "YES".equalsIgnoreCase(nullable)) {
            return;
        }
        executeDdl(connection, "ALTER TABLE `" + ROSTER_ENTRY_TABLE
            + "` MODIFY COLUMN `slot` bigint NULL COMMENT '座位号 1..N;NULL=待落位(多入口汇合时由导播拖到座位上)'");
        log.info("schema 自检: 已放宽 {}.slot 为可空(支持多入口汇合的待落位行)", ROSTER_ENTRY_TABLE);
    }

    /**
     * 一次性数据搬迁:把老库里 {@code t_stage.roster_config_json} 的来源组搬进
     * {@code t_stage_roster_group}(新库不再有这一列)。
     *
     * <p>只搬"目标赛段在边表里一条组都没有"的赛段,搬完把该赛段的 JSON 置空作为"已搬迁"标记——
     * 这样只搬一次;否则之后现场手工删掉的组会在下次重启时复活。</p>
     *
     * <p>搬迁时按旧代码的"字段长相"补 {@code generated} 标记:旧数据里那批默认衔接本来就是按长相生成的。
     * 新写入的行一律由业务代码显式给 {@code generated},不再猜。</p>
     */
    private void migrateRosterGroups(Connection connection, boolean sqlite) {
        try {
            boolean groupTableExists = sqlite
                ? sqliteTableExists(connection, ROSTER_GROUP_TABLE)
                : tableExists(connection, ROSTER_GROUP_TABLE);
            if (!groupTableExists) {
                return;
            }
            if (!existingColumns(connection, "t_stage", sqlite).contains("roster_config_json")) {
                return;   // 新库没有这一列,无需搬迁
            }
            Set<Long> targetsWithGroups = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                     "SELECT DISTINCT target_stage_id FROM `" + ROSTER_GROUP_TABLE + "`")) {
                while (rs.next()) {
                    targetsWithGroups.add(rs.getLong(1));
                }
            }
            List<long[]> stageRows = new ArrayList<>();
            List<String> jsonRows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id, tenant_id, tournament_id, roster_config_json"
                     + " FROM t_stage WHERE roster_config_json IS NOT NULL")) {
                while (rs.next()) {
                    stageRows.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
                    jsonRows.add(rs.getString(4));
                }
            }
            int migrated = 0;
            for (int i = 0; i < stageRows.size(); i++) {
                long[] stage = stageRows.get(i);
                if (targetsWithGroups.contains(stage[0])) {
                    continue;
                }
                List<TStageRosterGroupBo> groups;
                try {
                    groups = StageRosterGroupCodec.parse(jsonRows.get(i));
                } catch (RuntimeException e) {
                    log.warn("来源组搬迁:赛段[{}]的 JSON 无法解析,已跳过({})", stage[0], e.getMessage());
                    continue;
                }
                int order = 1;
                for (TStageRosterGroupBo g : groups) {
                    insertGroupRow(connection, stage, g, order++);
                }
                clearRosterJson(connection, stage[0]);
                migrated++;
            }
            if (migrated > 0) {
                log.info("schema 自检: 已把 {} 个赛段的来源组从 roster_config_json 搬迁到 {}",
                    migrated, ROSTER_GROUP_TABLE);
            }
        } catch (Exception e) {
            // 搬迁失败不影响启动:业务仍能跑,只是老数据没带过来
            log.warn("来源组一次性搬迁失败(不影响启动): {}", e.getMessage());
        }
    }

    /**
     * 一次性数据搬迁(逐选手赛制):老库把「一个选手一个 round」当成了回合,与新的
     * 「一场一个回合 + participant 承载选手」模型冲突。这里把每个海选/排名赛场次的
     * 多余回合合并成一个:先把打分明细 re-point 到保留回合,再删掉其余回合。
     *
     * <p>幂等:只处理「回合数 &gt; 1」的逐选手赛段的场次;新库每场只有一个回合,不受影响。
     * 座位制(淘汰/擂台/自由对抗)的 BO 多局是合法多回合,不在处理范围。</p>
     */
    private void collapsePerCompetitorRounds(Connection connection) {
        try {
            List<Long> matchIds = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                     "SELECT m.id FROM t_match m JOIN t_stage s ON m.stage_id = s.id"
                         + " WHERE s.stage_mode IN ('AUDITION','RANK')")) {
                while (rs.next()) {
                    matchIds.add(rs.getLong(1));
                }
            }
            int collapsed = 0;
            for (Long matchId : matchIds) {
                List<Long> roundIds = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM t_match_round WHERE match_id = ? ORDER BY round_sequence, id")) {
                    ps.setLong(1, matchId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            roundIds.add(rs.getLong(1));
                        }
                    }
                }
                if (roundIds.size() <= 1) {
                    continue;
                }
                long keep = roundIds.get(0);
                List<String> drop = new ArrayList<>();
                for (int i = 1; i < roundIds.size(); i++) {
                    drop.add(String.valueOf(roundIds.get(i)));
                }
                String dropIn = String.join(",", drop);
                executeDdl(connection, "UPDATE t_round_score SET round_id = " + keep
                    + " WHERE round_id IN (" + dropIn + ")");
                executeDdl(connection, "DELETE FROM t_match_round WHERE id IN (" + dropIn + ")");
                collapsed++;
            }
            if (collapsed > 0) {
                log.info("schema 自检: 已把 {} 个逐选手赛场次的多余轮次合并为一个回合(round),打分已重挂到保留回合",
                    collapsed);
            }
        } catch (Exception e) {
            // 搬迁失败不影响启动:业务仍能跑
            log.warn("逐选手轮次一次性合并失败(不影响启动): {}", e.getMessage());
        }
    }

    private static void insertGroupRow(Connection connection, long[] stage, TStageRosterGroupBo g, int order)
        throws SQLException {
        String sql = "INSERT INTO `" + ROSTER_GROUP_TABLE + "` (id, tenant_id, tournament_id, target_stage_id,"
            + " source_stage_id, result_filter, zone, round_no, rank_start, rank_end, rank_by_zone,"
            + " score_min, score_max, fill_mode, quota, order_by, sort_order, auto_generated, create_time)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, IdUtil.getSnowflakeNextId());
            ps.setLong(i++, stage[1]);
            ps.setLong(i++, stage[2]);
            ps.setLong(i++, stage[0]);
            if (g.getSourceStageId() == null) {
                ps.setNull(i++, Types.BIGINT);
            } else {
                ps.setLong(i++, g.getSourceStageId());
            }
            ps.setString(i++, g.getResultFilter());
            ps.setString(i++, g.getZone());
            if (g.getRound() == null) {
                ps.setNull(i++, Types.INTEGER);
            } else {
                ps.setInt(i++, g.getRound());
            }
            if (g.getRankStart() == null) {
                ps.setNull(i++, Types.INTEGER);
            } else {
                ps.setInt(i++, g.getRankStart());
            }
            if (g.getRankEnd() == null) {
                ps.setNull(i++, Types.INTEGER);
            } else {
                ps.setInt(i++, g.getRankEnd());
            }
            ps.setInt(i++, Boolean.TRUE.equals(g.getRankByZone()) ? 1 : 0);
            if (g.getScoreMin() == null) {
                ps.setNull(i++, Types.DECIMAL);
            } else {
                ps.setBigDecimal(i++, g.getScoreMin());
            }
            if (g.getScoreMax() == null) {
                ps.setNull(i++, Types.DECIMAL);
            } else {
                ps.setBigDecimal(i++, g.getScoreMax());
            }
            ps.setString(i++, g.getFillMode() == null ? "AUTO" : g.getFillMode());
            ps.setInt(i++, g.getQuota() == null ? 0 : g.getQuota());
            ps.setString(i++, g.getOrderBy());
            ps.setInt(i++, order);
            // 旧数据里"长得像默认衔接"的就是当年自动生成的那些,搬进来时补上标记
            ps.setInt(i++, g.looksLikeGeneratedDefault() ? 1 : 0);
            ps.setTimestamp(i, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private static void clearRosterJson(Connection connection, long stageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE t_stage SET roster_config_json = NULL WHERE id = ?")) {
            ps.setLong(1, stageId);
            ps.executeUpdate();
        }
    }

    /** SQLite 改列(去 NOT NULL)的唯一做法:按建表脚本重建同构新表并搬迁数据,索引随后由脚本重建 */
    private static void rebuildSqliteTable(Connection connection, String table, String createDdl, String tmpSuffix)
        throws SQLException {
        String tmp = table + tmpSuffix;
        Set<String> existing = existingColumns(connection, table, true);
        List<String> copyCols = new ArrayList<>();
        for (String col : parseColumnDefinitions(createDdl).keySet()) {
            if (existing.contains(col)) {
                copyCols.add("`" + col + "`");
            }
        }
        String columnList = String.join(", ", copyCols);
        executeDdl(connection, "DROP TABLE IF EXISTS `" + tmp + "`");
        executeDdl(connection, renameCreateTable(createDdl, tmp));
        if (!copyCols.isEmpty()) {
            executeDdl(connection, "INSERT INTO `" + tmp + "` (" + columnList + ") SELECT " + columnList
                + " FROM `" + table + "`");
        }
        executeDdl(connection, "DROP TABLE `" + table + "`");
        executeDdl(connection, "ALTER TABLE `" + tmp + "` RENAME TO `" + table + "`");
    }

    /** 把 CREATE TABLE 语句里的表名替换成新名(用于 SQLite 重建搬迁) */
    private static String renameCreateTable(String ddl, String newName) {
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(ddl);
        if (matcher.find()) {
            return ddl.substring(0, matcher.start(1)) + newName + ddl.substring(matcher.end(1));
        }
        return ddl;
    }

    private static void executeDdl(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /**
     * 表已存在时,把「脚本里有、库里没有」的列补上(只加列,不删不改)。
     *
     * <p>旧版本建过的表不会因为 {@code CREATE TABLE IF NOT EXISTS} 而更新结构,
     * 新版本给实体加了字段后,查询就会报 {@code no such column}。这里按脚本里的
     * 列定义做增量补齐,老库无需删库重建。</p>
     *
     * @return 实际补齐的列数
     */
    private static int addMissingColumns(Connection connection, String table, String ddl, boolean sqlite)
        throws SQLException {
        Map<String, String> scriptColumns = parseColumnDefinitions(ddl);
        if (scriptColumns.isEmpty()) {
            return 0;
        }
        Set<String> existing = existingColumns(connection, table, sqlite);
        int added = 0;
        for (Map.Entry<String, String> entry : scriptColumns.entrySet()) {
            if (existing.contains(entry.getKey())) {
                continue;
            }
            String sql = "ALTER TABLE `" + table + "` ADD COLUMN " + entry.getValue();
            try {
                executeDdl(connection, sql);
                added++;
                log.info("自动补列成功: {}.{}", table, entry.getKey());
            } catch (SQLException e) {
                // 例如 SQLite 不允许 ADD COLUMN 带 NOT NULL 且无默认值:打印可直接执行的 SQL 便于手工处理
                log.warn("自动补列失败: {}.{} - {};可手动执行: {}", table, entry.getKey(), e.getMessage(), sql);
            }
        }
        return added;
    }

    /** 读取已有表的列名(统一小写,忽略大小写差异) */
    private static Set<String> existingColumns(Connection connection, String table, boolean sqlite)
        throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        if (sqlite) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA table_info(`" + table + "`)")) {
                while (rs.next()) {
                    columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
                }
            }
            return columns;
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, connection.getCatalog());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    /**
     * 从 CREATE TABLE 语句解析列定义:列名(小写) → 原始定义片段。
     *
     * <p>跳过 {@code PRIMARY KEY / KEY / INDEX / UNIQUE / CONSTRAINT / FOREIGN KEY / CHECK}
     * 这类表级约束;切分时跟踪括号深度与引号状态,避免
     * {@code decimal(10,2)}、{@code COMMENT '标签A,标签B'} 里的逗号被误当分隔符。</p>
     */
    static Map<String, String> parseColumnDefinitions(String ddl) {
        int open = ddl.indexOf('(');
        int close = open < 0 ? -1 : matchingParen(ddl, open);
        if (close < 0) {
            return Map.of();
        }
        Map<String, String> columns = new LinkedHashMap<>();
        for (String raw : splitTopLevel(ddl.substring(open + 1, close))) {
            String definition = raw.trim();
            // 脚本里允许在列定义之间夹注释行(`-- xxx` / `# xxx`);注释不是列,
            // 但同一段里紧随其后的列定义要保留下来。
            while (definition.startsWith("--") || definition.startsWith("#")) {
                int newline = definition.indexOf('\n');
                if (newline < 0) {
                    definition = "";
                    break;
                }
                definition = definition.substring(newline + 1).trim();
            }
            if (definition.isEmpty()) {
                continue;
            }
            String head = definition.toUpperCase(Locale.ROOT);
            if (head.startsWith("PRIMARY KEY") || head.startsWith("UNIQUE") || head.startsWith("KEY ")
                || head.startsWith("INDEX ") || head.startsWith("CONSTRAINT")
                || head.startsWith("FOREIGN KEY") || head.startsWith("CHECK ")) {
                continue;
            }
            String name = firstToken(definition);
            if (!name.isEmpty()) {
                columns.put(name.toLowerCase(Locale.ROOT), definition);
            }
        }
        return columns;
    }

    /** 找到与 {@code openIndex} 处 '(' 配对的 ')' 下标(忽略引号内的括号),找不到返回 -1 */
    private static int matchingParen(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 按顶层逗号切分(忽略括号与引号内部的逗号) */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** 取定义里的第一个 token 作为列名,去掉反引号/双引号/方括号 */
    private static String firstToken(String definition) {
        int end = 0;
        while (end < definition.length() && !Character.isWhitespace(definition.charAt(end))) {
            end++;
        }
        return definition.substring(0, end)
            .replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
    }

    /**
     * 从 classpath 或工作目录加载建表脚本(jar 内与源码目录两种运行形态都支持)。
     */
    private String loadSqlScript(String classpathResource, String filesystemPath) {
        ClassPathResource resource = new ClassPathResource(classpathResource);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取 classpath:{} 失败: {}", classpathResource, e.getMessage());
            }
        }
        Path file = Path.of(filesystemPath);
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取 {} 失败: {}", file.toAbsolutePath(), e.getMessage());
            }
        }
        throw new IllegalStateException("未找到建表脚本 " + classpathResource + "(classpath 与工作目录均不存在)");
    }

    /**
     * 解析建表脚本,只保留 CREATE TABLE 语句(忽略 DROP TABLE / SET / 注释等,
     * 避免误删已有数据)。语句前若有注释或 MySQL 版本化 SET,只取 CREATE 起的片段。
     */
    static List<String> parseCreateTableStatements(String script) {
        List<String> ddlList = new ArrayList<>();
        for (String part : splitStatements(script)) {
            String statement = part.trim();
            if (statement.isEmpty()) {
                continue;
            }
            Matcher matcher = CREATE_TABLE_PATTERN.matcher(statement);
            if (matcher.find()) {
                ddlList.add(statement.substring(matcher.start()));
            }
        }
        return ddlList;
    }

    /**
     * 解析建表脚本中的 CREATE [UNIQUE] INDEX 语句(SQLite 独立索引),
     * 同样只取 CREATE 起的片段,丢弃行首注释。
     */
    static List<String> parseCreateIndexStatements(String script) {
        List<String> indexList = new ArrayList<>();
        for (String part : splitStatements(script)) {
            String statement = part.trim();
            if (statement.isEmpty()) {
                continue;
            }
            Matcher matcher = CREATE_INDEX_PATTERN.matcher(statement);
            if (matcher.find()) {
                indexList.add(statement.substring(matcher.start()));
            }
        }
        return indexList;
    }

    /**
     * 按分号切分 SQL 脚本,但忽略单引号/双引号/反引号内部的分号。
     *
     * <p>此前直接 {@code script.split(";")}:注释里出现分号(如
     * {@code COMMENT '参赛方ID;轮空占位为 NULL'})会把整条 CREATE TABLE 截断,
     * 该表后面的列就再也补不出来。</p>
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        statements.add(current.toString());
        return statements;
    }

    static String extractTableName(String ddl) {
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(ddl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalStateException("无法从建表语句中解析表名: " + ddl);
    }

    /** 从 CREATE INDEX 语句中解析索引名 */
    static String extractIndexName(String ddl) {
        Matcher matcher = CREATE_INDEX_PATTERN.matcher(ddl);
        if (matcher.find()) {
            return matcher.group(2);
        }
        throw new IllegalStateException("无法从建索引语句中解析索引名: " + ddl);
    }

    /** 转换为 IF NOT EXISTS,多实例并发启动时也不会互相冲突 */
    static String withIfNotExists(String ddl) {
        if (CREATE_TABLE_IF_NOT_EXISTS_PATTERN.matcher(ddl).find()) {
            return ddl;
        }
        return ddl.replaceFirst("(?im)^\\s*CREATE\\s+TABLE\\s+", "CREATE TABLE IF NOT EXISTS ");
    }

    /** 转换为 CREATE [UNIQUE] INDEX IF NOT EXISTS,重复执行不会报错 */
    static String withIfNotExistsIndex(String ddl) {
        if (CREATE_INDEX_IF_NOT_EXISTS_PATTERN.matcher(ddl).find()) {
            return ddl;
        }
        return ddl.replaceFirst("(?im)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+", "CREATE $1INDEX IF NOT EXISTS ");
    }

    /**
     * SQLite 文件数据库:自动创建数据文件所在目录(驱动不会创建父目录)。
     * 内存库(:memory:)与 URI 参数形式均安全跳过。
     */
    private void ensureSqliteParentDirectory() {
        String location = jdbcUrl.substring("jdbc:sqlite:".length());
        if (location.isBlank() || location.contains(":memory:")) {
            return;
        }
        if (location.startsWith("file:")) {
            location = location.substring("file:".length());
            int query = location.indexOf('?');
            if (query >= 0) {
                location = location.substring(0, query);
            }
        }
        if (location.isBlank() || location.equals(":memory:")) {
            return;
        }
        Path parent = Path.of(location).toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                log.warn("创建 SQLite 数据目录失败({}),请确认目录可写: {}", parent, e.getMessage());
            }
        }
    }

    /**
     * 去掉 URL 中的库名,保留主机/端口与连接参数,用于连接实例级建库。
     * 例如 jdbc:mysql://host:3306/game_db?a=b -> jdbc:mysql://host:3306/?a=b
     */
    private static String stripDatabaseName(String url) {
        int queryIndex = url.indexOf('?');
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        String params = queryIndex >= 0 ? url.substring(queryIndex) : "";
        int slash = base.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        return base.substring(0, slash + 1) + params;
    }

    private static String extractDatabaseName(String url) {
        int queryIndex = url.indexOf('?');
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : "";
    }
}
