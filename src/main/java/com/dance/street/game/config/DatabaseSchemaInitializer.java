package com.dance.street.game.config;

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
import java.util.ArrayList;
import java.util.List;
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
 *   <li>SQLite 额外执行脚本中的 {@code CREATE INDEX IF NOT EXISTS}(MySQL 索引内联在表定义中);</li>
 *   <li>可开关: {@code app.schema-init.enabled=false} 或环境变量
 *       {@code SCHEMA_INIT_ENABLED=false} 关闭。</li>
 * </ol>
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
        try (Connection connection = dataSource.getConnection()) {
            for (String ddl : ddlList) {
                String table = extractTableName(ddl);
                try {
                    if (tableExists(connection, table)) {
                        existed++;
                        log.debug("数据表已存在,跳过: {}", table);
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
        } catch (SQLException e) {
            log.error("连接数据库检查表结构失败: {}", e.getMessage());
            return;
        }
        if (failed > 0) {
            log.warn("schema 自检完成: 已存在 {} 张,新建 {} 张,失败 {} 张", existed, created, failed);
        } else {
            log.info("schema 自检完成: 已存在 {} 张,新建 {} 张", existed, created);
        }
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
        try (Connection connection = dataSource.getConnection()) {
            for (String ddl : ddlList) {
                String table = extractTableName(ddl);
                try {
                    if (sqliteTableExists(connection, table)) {
                        existed++;
                        log.debug("数据表已存在,跳过: {}", table);
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
        } catch (SQLException e) {
            log.error("连接 SQLite 检查表结构失败: {}", e.getMessage());
            return;
        }
        if (failed > 0) {
            log.warn("schema 自检完成: 已存在 {} 张,新建 {} 张,失败 {} 项", existed, created, failed);
        } else {
            log.info("schema 自检完成: 已存在 {} 张,新建 {} 张", existed, created);
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

    private static void executeDdl(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
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
        for (String part : script.split(";")) {
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
        for (String part : script.split(";")) {
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
