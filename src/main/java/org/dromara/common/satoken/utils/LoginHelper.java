package org.dromara.common.satoken.utils;

import cn.dev33.satoken.stp.StpUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 登录助手(精简版):
 * 单账号登录场景下,登录时把 userId 写入 JWT 的 "userId" 载荷,
 * SseController 等通过 getUserId() 读取。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginHelper {

    public static final String USER_KEY = "userId";

    public static Long getUserId() {
        Object value = StpUtil.getExtra(USER_KEY);
        return value == null ? null : Long.valueOf(value.toString());
    }
}
