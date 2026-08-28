package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.dance.street.game.service.LoginAccountService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 修改密码:必须登录(否则任何人都可无限试旧密码),校验旧密码后持久化新密码;
 * 仍在使用默认密码时允许不传旧密码(首次登录强制修改场景)。
 */
@Validated
@RequiredArgsConstructor
@RestController
public class PasswordController {

    private final LoginAccountService loginAccountService;

    @SaCheckLogin
    @PostMapping("/changePassword")
    public R<Void> changePassword(@Validated @RequestBody ChangePasswordBody body) {
        loginAccountService.changePassword(body.getOldPassword(), body.getNewPassword());
        // 登录凭证为无状态 JWT,改密后无需重新登录,前端留在当前页面直接使用
        return R.ok("密码修改成功");
    }

    /**
     * 查询当前账号是否仍在使用默认密码(登录后刷新页面时前端据此恢复强制改密弹窗)。
     */
    @SaCheckLogin
    @GetMapping("/login/passwordStatus")
    public R<Map<String, Object>> passwordStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("defaultPassword", loginAccountService.isDefaultPassword());
        return R.ok(data);
    }

    @Data
    public static class ChangePasswordBody {
        /** 仍在使用默认密码时可为空,否则必填 */
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 32, message = "新密码长度必须介于 8 和 32 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码必须同时包含字母和数字")
        private String newPassword;
    }
}
