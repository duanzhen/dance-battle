package com.dance.street.game.service;

import cn.hutool.crypto.digest.BCrypt;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * 单账号登录凭证存储。
 *
 * <p>登录密码修改后持久化到数据库 {@code t_login_account} 表;
 * {@code login.username / login.password}(环境变量 LOGIN_USERNAME / LOGIN_PASSWORD)
 * 仅作为首次运行时的默认账号密码,初始化之后以数据库记录为准。</p>
 */
@Slf4j
@Service
public class LoginAccountService {

    /** 单账号体系固定账号 id */
    private static final long ACCOUNT_ID = 1L;

    /** 内置默认密码:未通过环境变量自定义时的系统默认值 */
    private static final String BUILTIN_DEFAULT_PASSWORD = "123456";

    private final JdbcTemplate jdbcTemplate;

    @Value("${login.username:admin}")
    private String defaultUsername;

    @Value("${login.password:123456}")
    private String defaultPassword;

    @Autowired(required = false)
    private Environment environment;

    public LoginAccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 判断账号当前是否仍在使用“默认密码”。
     *
     * <p>规则：未传入环境变量 {@code LOGIN_PASSWORD}，或传入的值等于内置默认密码时，
     * 若数据库中密码仍等于对应默认值，视为默认密码，首次登录需强制修改；
     * 显式传入其他自定义密码时不算默认密码。</p>
     */
    public boolean isDefaultPassword() {
        String envPassword = environment == null ? null : environment.getProperty("LOGIN_PASSWORD");
        if (envPassword != null && !envPassword.equals(BUILTIN_DEFAULT_PASSWORD)) {
            // 运维显式自定义了非默认密码 → 不算默认密码
            return false;
        }
        String effectiveDefault = envPassword != null ? BUILTIN_DEFAULT_PASSWORD : defaultPassword;
        String hash = selectPasswordHash();
        return hash == null || BCrypt.checkpw(effectiveDefault, hash);
    }

    /**
     * 校验用户名/密码。
     *
     * <p>首次运行或表中尚无账号记录时,回退到配置文件默认值并尝试落库;
     * 数据库不可用(如表缺失)时降级为配置校验,不影响登录。</p>
     */
    public boolean verify(String username, String password) {
        if (username == null || !defaultUsername.equals(username)) {
            return false;
        }
        String hash = selectPasswordHash();
        if (hash == null) {
            boolean ok = password != null && defaultPassword.equals(password);
            if (ok) {
                seedIfAbsent();
            }
            return ok;
        }
        return password != null && BCrypt.checkpw(password, hash);
    }

    /**
     * 修改密码:校验旧密码后,将新密码 BCrypt 加密写入数据库。
     */
    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        if (isDefaultPassword()) {
            // 仍在使用默认密码:首次强制修改,无需校验旧密码,但不允许继续使用默认密码
            if (newPassword != null
                && (newPassword.equals(defaultPassword) || newPassword.equals(BUILTIN_DEFAULT_PASSWORD))) {
                throw new ServiceException("新密码不能与默认密码相同");
            }
        } else {
            if (oldPassword == null || !verify(defaultUsername, oldPassword)) {
                throw new ServiceException("旧密码错误");
            }
            if (oldPassword.equals(newPassword)) {
                throw new ServiceException("新密码不能与旧密码相同");
            }
        }
        String hash = BCrypt.hashpw(newPassword);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            int updated = jdbcTemplate.update(
                "UPDATE t_login_account SET password = ?, update_time = ? WHERE username = ?",
                hash, now, defaultUsername);
            if (updated == 0) {
                jdbcTemplate.update(
                    "INSERT INTO t_login_account (id, username, password, create_time, update_time) VALUES (?, ?, ?, ?, ?)",
                    ACCOUNT_ID, defaultUsername, hash, now, now);
            }
        } catch (Exception e) {
            log.error("修改密码写入数据库失败", e);
            throw new ServiceException("密码修改失败,请稍后重试");
        }
    }

    /**
     * 查询数据库中的密码哈希;表缺失或数据库不可用时返回 null(降级配置校验)。
     */
    private String selectPasswordHash() {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT password FROM t_login_account WHERE username = ? LIMIT 1",
                String.class, defaultUsername);
        } catch (Exception e) {
            log.warn("读取登录账号密码失败(将回退到配置文件默认值): {}", e.getMessage());
            return null;
        }
    }

    /** 首次运行时用配置文件默认账号密码初始化(仅当表中无该账号) */
    private void seedIfAbsent() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_login_account WHERE username = ?",
                Integer.class, defaultUsername);
            if (count == null || count == 0) {
                Timestamp now = new Timestamp(System.currentTimeMillis());
                jdbcTemplate.update(
                    "INSERT INTO t_login_account (id, username, password, create_time, update_time) VALUES (?, ?, ?, ?, ?)",
                    ACCOUNT_ID, defaultUsername, BCrypt.hashpw(defaultPassword), now, now);
            }
        } catch (Exception e) {
            log.warn("初始化登录账号失败(继续使用配置文件默认值): {}", e.getMessage());
        }
    }
}
