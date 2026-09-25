package com.dance.street.game.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 简化权限体系：单账号登录后授予通配权限 {@code *}(Sa-Token 匹配任意权限)，
 * 因此复制过来的 @SaCheckPermission("game:xxx") 及后续新增的权限点均能通过。
 *
 * @author duane
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
