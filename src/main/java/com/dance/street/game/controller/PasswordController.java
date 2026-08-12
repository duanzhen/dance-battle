package com.dance.street.game.controller;

import com.dance.street.game.service.LoginAccountService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 修改密码:需要登录态,校验旧密码后持久化新密码。
 */
@Validated
@RequiredArgsConstructor
@RestController
public class PasswordController {

    private final LoginAccountService loginAccountService;

    @PostMapping("/changePassword")
    public R<Void> changePassword(@Validated @RequestBody ChangePasswordBody body) {
        loginAccountService.changePassword(body.getOldPassword(), body.getNewPassword());
        return R.ok("密码修改成功,请重新登录");
    }

    @Data
    public static class ChangePasswordBody {
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "新密码长度必须介于 6 和 32 位之间")
        private String newPassword;
    }
}
