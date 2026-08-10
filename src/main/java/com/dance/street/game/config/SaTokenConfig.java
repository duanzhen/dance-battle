package com.dance.street.game.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器：启用 @SaCheckPermission/@SaIgnore 注解鉴权
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 切换为无状态 JWT(Stateless 模式):
     * 登录态、有效期全部编码在 token 中,服务端不存储会话,应用重启不会导致登录失效。
     */
    @PostConstruct
    public void initJwtStpLogic() {
        StpUtil.setStpLogic(new StpLogicJwtForStateless());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
            .addPathPatterns("/**");
    }
}
