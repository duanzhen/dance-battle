package com.dance.street.game.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 简化权限体系：单账号登录后授予全部 game:* 权限，
 * 保证复制过来的 @SaCheckPermission("game:xxx") 均能通过。
 */
@Component
public class PermissionStpInterface implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of("*");
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of("admin");
    }
}
