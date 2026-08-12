package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 路由回退回归测试:前端 history 模式路由刷新时,即使路径与 POST-only 后端接口
 * (如 /login)相同,GET 请求也应回退到 index.html,而不是返回 405。
 */
class SpaControllerTest {

    /** 模拟与前端 /login 路由同路径的仅 POST 接口 */
    @RestController
    static class StubLoginController {
        @PostMapping("/login")
        public String login() {
            return "ok";
        }
    }

    /** 当前实现:GET 兜底注册在 RequestMappingHandlerMapping 中 */
    @Configuration
    @EnableWebMvc
    static class SpaControllerConfig implements WebMvcConfigurer {
        @Bean
        SpaController spaController() {
            return new SpaController();
        }

        @Bean
        StubLoginController stubLoginController() {
            return new StubLoginController();
        }
    }

    /** 旧实现:仅靠 ViewControllerRegistry 回退(复现 405) */
    @Configuration
    @EnableWebMvc
    static class ViewControllerConfig implements WebMvcConfigurer {
        @Bean
        StubLoginController stubLoginController() {
            return new StubLoginController();
        }

        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addViewController("/{path:[^\\.]*}").setViewName("forward:/index.html");
            registry.addViewController("/**/{path:[^\\.]*}").setViewName("forward:/index.html");
        }
    }

    private MockMvc buildMvc(Class<?> configClass) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(configClass);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void getLoginRefreshesToIndexHtmlInsteadOf405() throws Exception {
        MockMvc mvc = buildMvc(SpaControllerConfig.class);

        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void postLoginStillHitsApi() throws Exception {
        MockMvc mvc = buildMvc(SpaControllerConfig.class);

        mvc.perform(post("/login"))
            .andExpect(status().isOk());
    }

    @Test
    void deepFrontendRouteFallsBackToIndexHtml() throws Exception {
        MockMvc mvc = buildMvc(SpaControllerConfig.class);

        mvc.perform(get("/game/list"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/tournament/projection"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void viewControllerOnlyFallbackReturns405OnLoginRefresh() throws Exception {
        MockMvc mvc = buildMvc(ViewControllerConfig.class);

        int status = mvc.perform(get("/login"))
            .andReturn()
            .getResponse()
            .getStatus();
        assertEquals(405, status);
    }
}
