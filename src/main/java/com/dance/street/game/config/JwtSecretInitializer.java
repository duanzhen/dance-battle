package com.dance.street.game.config;

import cn.dev33.satoken.SaManager;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * JWT 签名密钥解析与持久化。
 *
 * <p>优先级:JWT_SECRET_KEY 环境变量(显式指定) &gt; 密钥文件(首次运行生成后持久化) &gt; 本次随机生成。</p>
 *
 * <p>密钥文件默认写入 ./data/jwt/dance-game-jwt-secret.key(可用 JWT_SECRET_FILE 覆盖),
 * 首次运行自动生成随机密钥并落盘,后续重启直接读取,保证无状态 JWT 在重启后不失效。
 * Docker 中默认落在 /data/jwt(与 SQLite 同卷),部署时挂载该卷持久化即可;
 * 否则容器重建会重新生成密钥、旧 token 全部失效。</p>
 */
@Slf4j
@Component
public class JwtSecretInitializer implements SmartInitializingSingleton {

    public static final String ENV_SECRET_KEY = "JWT_SECRET_KEY";
    public static final String ENV_SECRET_FILE = "JWT_SECRET_FILE";
    public static final String DEFAULT_SECRET_FILE = "./data/jwt/dance-game-jwt-secret.key";

    /** 48 字节 -> 64 位 Base64 URL 字符(HS256 及以上强度) */
    private static final int SECRET_BYTES = 48;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 全部单例 Bean 实例化完成后执行(此时 Sa-Token 已用 yml 配置初始化全局 SaTokenConfig),
     * 在 Web 服务器对外服务前覆盖 jwt-secret-key。
     */
    @Override
    public void afterSingletonsInstantiated() {
        String secret = resolveSecret();
        SaManager.getConfig().setJwtSecretKey(secret);
        log.info("Sa-Token JWT 密钥已就绪,来源: {}", secretSource());
    }

    private String resolveSecret() {
        // 1. 显式环境变量优先级最高(多实例共享密钥、外部密钥管理等场景)
        String envSecret = System.getenv(ENV_SECRET_KEY);
        if (StringUtils.isNotBlank(envSecret)) {
            return envSecret.trim();
        }
        // 2. 已持久化的密钥文件
        Path file = secretFile();
        if (Files.isRegularFile(file)) {
            try {
                String content = Files.readString(file).trim();
                if (StringUtils.isNotBlank(content)) {
                    return content;
                }
                log.warn("JWT 密钥文件内容为空,将重新生成: {}", file);
            } catch (IOException e) {
                log.warn("读取 JWT 密钥文件失败,将重新生成: {} - {}", file, e.getMessage());
            }
        }
        // 3. 首次运行:随机生成并持久化,后续重启复用
        String secret = generate();
        writeSecretFile(file, secret);
        return secret;
    }

    private Path secretFile() {
        String path = System.getenv(ENV_SECRET_FILE);
        return Path.of(StringUtils.isNotBlank(path) ? path.trim() : DEFAULT_SECRET_FILE);
    }

    private String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void writeSecretFile(Path file, String secret) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, secret);
            restrictPermissions(file);
            log.info("已生成随机 JWT 密钥并写入: {}", file);
        } catch (IOException e) {
            log.warn("写入 JWT 密钥文件失败({}): {},本次运行使用临时随机密钥,重启后旧 token 会失效",
                file, e.getMessage());
        }
    }

    private void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统(如 Windows),退化为 File API 尽力限制
            file.toFile().setReadable(false, false);
            file.toFile().setWritable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(true, true);
        } catch (IOException e) {
            log.warn("设置密钥文件权限失败: {} - {}", file, e.getMessage());
        }
    }

    private String secretSource() {
        if (StringUtils.isNotBlank(System.getenv(ENV_SECRET_KEY))) {
            return "JWT_SECRET_KEY 环境变量";
        }
        Path file = secretFile();
        if (Files.isRegularFile(file)) {
            return "密钥文件 " + file;
        }
        return "本次随机生成(写入失败,未持久化)";
    }
}
