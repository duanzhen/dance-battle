package com.dance.street.game.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退:前端 history 模式路由(如 /game/list、/tournament/projection、/login)刷新时
 * 统一转发到 index.html,由前端路由接管。
 *
 * <p>必须注册为 {@code @GetMapping} 控制器方法而不是 ViewControllerRegistry:
 * 当某路径同时存在仅支持 POST 的后端接口时(如 /login),Spring 检测到“路径匹配但方法不匹配”
 * 会直接抛出 405,不会继续回退到 view controller,导致刷新报错。
 * 注册 GET 兜底后,GET /login 会命中兜底映射,与 POST /login 互不冲突;
 * 具体接口(@RequestMapping)路径更具体,优先级更高,不会被覆盖;
 * 带扩展名的静态资源(js/css/图片等)由 {@code [^\\.]*} 排除,不受影响。</p>
 */
@Controller
public class SpaController {

    @GetMapping({"/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String spaFallback() {
        return "forward:/index.html";
    }
}
