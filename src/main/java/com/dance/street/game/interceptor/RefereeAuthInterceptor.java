package com.dance.street.game.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.service.ITRefereeService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 裁判认证拦截器：从 Authorization header 提取 authKey，校验后存入 request attribute
 *
 * <p>普通接口只认 {@code Authorization: Bearer <authKey>},避免 authKey 出现在 URL/访问日志;
 * 仅 SSE 连接(EventSource 无法自定义请求头)例外,允许 query 参数携带。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class RefereeAuthInterceptor implements HandlerInterceptor {

    private final ITRefereeService refereeService;

    public static final String REFEREE_ATTR = "refereeInfo";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authKey = resolveAuthKey(request);
        if (authKey == null || authKey.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"请通过 Authorization: Bearer <authKey> 提供裁判认证凭证\"}");
            return false;
        }

        TRefereeVo referee = refereeService.findByAuthKey(authKey);
        if (referee == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"认证凭证无效\"}");
            return false;
        }

        request.setAttribute(REFEREE_ATTR, referee);
        return true;
    }

    /**
     * 解析认证凭证:普通接口仅接受 Authorization 头;SSE 连接(EventSource 无法自定义请求头)允许 query 参数。
     */
    private String resolveAuthKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        if (request.getRequestURI().endsWith("/sse")) {
            return request.getParameter("authKey");
        }
        return null;
    }
}
