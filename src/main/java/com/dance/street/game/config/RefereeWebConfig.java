package com.dance.street.game.config;

import lombok.RequiredArgsConstructor;
import com.dance.street.game.interceptor.RefereeAuthInterceptor;
import com.dance.street.game.interceptor.DirectorAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 裁判端/手机导播台 WebMvc 配置：注册 authKey 认证拦截器(各自独立的 auth_key)
 *
 * @author duane
 */
@Configuration
@RequiredArgsConstructor
public class RefereeWebConfig implements WebMvcConfigurer {

    private final RefereeAuthInterceptor refereeAuthInterceptor;
    private final DirectorAuthInterceptor directorAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refereeAuthInterceptor)
            .addPathPatterns("/game/referee-match/**")
            .order(0);
        registry.addInterceptor(directorAuthInterceptor)
            .addPathPatterns("/game/director/**")
            .order(0);
    }
}
