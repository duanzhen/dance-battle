package com.dance.street.game.service;

import cn.hutool.crypto.digest.BCrypt;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    private final JdbcTemplate jdbcTemplate;

    @Value("${login.username:admin}")
    private String defaultUsername;

    @Value("${login.password:123456}")
    private String defaultPassword;

    public LoginAccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    public void changePassword(String oldPassword, String newPassword) {
        if (oldPassword != null && oldPassword.equals(newPassword)) {
            throw new ServiceException("新密码不能与旧密码相同");
        }
        if (!verify(defaultUsername, oldPassword)) {
            throw new ServiceException("旧密码错误");
        }
        String hash = BCrypt.hashpw(newPassword);
        try {
            jdbcTemplate.update("""
                INSERT INTO t_login_account (id, username, password, create_time, update_time)
                VALUES (?, ?, ?, NOW(), NOW())
                ON DUPLICATE KEY UPDATE password = ?, update_time = NOW()
                """, ACCOUNT_ID, defaultUsername, hash, hash);
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
            jdbcTemplate.update("""
                INSERT IGNORE INTO t_login_account (id, username, password, create_time, update_time)
                VALUES (?, ?, ?, NOW(), NOW())
                """, ACCOUNT_ID, defaultUsername, BCrypt.hashpw(defaultPassword));
        } catch (Exception e) {
            log.warn("初始化登录账号失败(继续使用配置文件默认值): {}", e.getMessage());
        }
    }
}
