package com.dance.street.game.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由回退:前端 history 模式路由(如 /game/list、/tournament/projection)刷新时
 * 若后端没有对应接口,统一转发到 index.html,由前端路由接管。
 * 具体接口(@RequestMapping)优先级更高,不会被覆盖;带扩展名的静态资源不受影响。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/**/{path:[^\\.]*}").setViewName("forward:/index.html");
    }
}
