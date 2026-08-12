package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.dance.street.game.config.LoginIpRateLimiter;
import com.dance.street.game.service.LoginAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 单账号登录：用户名/密码配置在 application.yml 的 login.*
 */
@SaIgnore
@Validated
@RequiredArgsConstructor
@RestController
public class AuthController {

    private final LoginIpRateLimiter loginIpRateLimiter;
    private final LoginAccountService loginAccountService;

    @Value("${login.username}")
    private String username;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Validated @RequestBody LoginBody body, HttpServletRequest request) {
        String ip = loginIpRateLimiter.resolveIp(request);
        if (loginIpRateLimiter.isBlocked(ip)) {
            throw new ServiceException(loginIpRateLimiter.blockedMessage(ip));
        }
        if (!loginAccountService.verify(body.getUsername(), body.getPassword())) {
            loginIpRateLimiter.recordFailure(ip);
            throw new ServiceException("用户名或密码错误");
        }
        loginIpRateLimiter.clear(ip);
        // 单账号:固定 loginId=1,userId 写入 JWT 载荷,供 LoginHelper.getUserId() 使用
        StpUtil.login(1L, new SaLoginModel().setExtra("userId", 1L));
        Map<String, Object> data = new HashMap<>();
        data.put("token", StpUtil.getTokenValue());
        data.put("username", username);
        data.put("defaultPassword", loginAccountService.isDefaultPassword());
        return R.ok(data);
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        StpUtil.logout();
        return R.ok();
    }

    @Data
    public static class LoginBody {
        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
