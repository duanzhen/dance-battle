package com.dance.street.game.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 并发护栏必须真的落到连接上(不只是拼在 URL 字符串里)。
 *
 * <p>回归的事故:多连接 + 事务「先读后写」时,SQLite 的锁升级不走 busy_timeout,
 * 一旦别的连接持锁就立刻抛 {@code SQLITE_BUSY}。修法是让每个连接都带上
 * WAL + busy_timeout + 事务 IMMEDIATE,并且事务开始(setAutoCommit(false))即取写锁。</p>
 *
 * <p>本用例走的是真实链路:环境后处理器解析 {@code app.db.type=sqlite} →
 * 给 SQLite 连接补齐参数 → Spring 交给 Hikari → 驱动建连接,最后直接查 pragma 验证。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true",
    "app.db.type=sqlite",
    "app.db.sqlite-fallback-url=jdbc:sqlite:target/sqlite-pragmas.db"
})
class SqliteConnectionPragmasTest {

    private static final String DB_PATH = "target/sqlite-pragmas.db";

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void everySqliteConnectionCarriesConcurrencyGuards() throws Exception {
        try (Connection cx = dataSource.getConnection(); Statement st = cx.createStatement()) {
            assertEquals("wal", queryString(st, "pragma journal_mode"), "应为 WAL:读者不阻塞写者");
            assertEquals(10000, queryInt(st, "pragma busy_timeout"), "应忙等 10s 而不是立刻失败");

            // Spring 开事务 = setAutoCommit(false):IMMEDIATE 模式下这一步就取写锁,
            // 于是锁竞争走 busy_timeout 重试,而不是在读锁升级时被直接判死锁
            SQLiteConnection sqlite = cx.unwrap(SQLiteConnection.class);
            cx.setAutoCommit(false);
            assertEquals(SQLiteConfig.TransactionMode.IMMEDIATE, sqlite.getCurrentTransactionMode(),
                "事务应以 BEGIN IMMEDIATE 开始");
            cx.rollback();
        }
    }

    /**
     * 复现用户报的那个错误:应用保存赛段时「先 SELECT 再 UPDATE」,此刻另一个连接正持写锁。
     *
     * <p>修之前:锁升级不走忙等,约 100ms 就抛 {@code [SQLITE_BUSY] database is locked};
     * 修之后:事务以 BEGIN IMMEDIATE 起,锁竞争走 busy_timeout,等对方提交后正常写入。</p>
     */
    @Test
    void writerWaitsForTheOtherWriterInsteadOfFailing() throws Exception {
        try (Connection setup = dataSource.getConnection(); Statement st = setup.createStatement()) {
            st.execute("create table if not exists lock_probe(id integer primary key, v text)");
            st.execute("delete from lock_probe");
            st.execute("insert into lock_probe(id, v) values(1, 'x')");
        }

        // 另一个连接(模拟并发请求/另一个页面):持写锁 1.2s 后提交
        Thread holder = new Thread(() -> {
            try (Connection cx = dataSource.getConnection()) {
                cx.setAutoCommit(false);   // BEGIN IMMEDIATE:这一刻就拿到写锁
                try (Statement st = cx.createStatement()) {
                    st.execute("update lock_probe set v = 'holder' where id = 1");
                }
                Thread.sleep(1200);
                cx.commit();
            } catch (Exception e) {
                throw new IllegalStateException("持锁连接异常", e);
            }
        });
        holder.start();
        Thread.sleep(300);

        long start = System.currentTimeMillis();
        try (Connection cx = dataSource.getConnection()) {
            cx.setAutoCommit(false);
            try (Statement st = cx.createStatement()) {
                // 先读后写:正是 UserMapper/TStageMapper 保存路径的形状
                try (ResultSet rs = st.executeQuery("select v from lock_probe where id = 1")) {
                    rs.next();
                }
                st.execute("update lock_probe set v = 'app' where id = 1");
            }
            cx.commit();
        }
        long waited = System.currentTimeMillis() - start;
        holder.join();

        assertTrue(waited >= 700, "应等待持锁连接释放后再写(实际 " + waited + "ms)");
    }

    private String queryString(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
