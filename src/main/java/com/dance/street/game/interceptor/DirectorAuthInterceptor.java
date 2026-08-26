package com.dance.street.game.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.service.ITTournamentService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 手机导播台认证拦截器：从 Authorization header(或 SSE 的 query 参数)提取赛事 authKey，
 * 校验后把赛事信息存入 request attribute。与裁判端独立，使用赛事自己的 auth_key。
 *
 * <p>REST 接口仅接受 {@code Authorization: Bearer <authKey>} 请求头;
 * 仅 SSE 连接(EventSource 无法自定义请求头)例外,允许 query 参数携带 authKey,
 * 与裁判端保持一致。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class DirectorAuthInterceptor implements HandlerInterceptor {

    private final ITTournamentService tournamentService;

    public static final String DIRECTOR_ATTR = "directorTournament";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authKey = resolveAuthKey(request);
        if (authKey == null || authKey.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"请通过 Authorization: Bearer <authKey> 提供赛事认证凭证\"}");
            return false;
        }

        TTournamentVo tournament = tournamentService.findByAuthKey(authKey);
        if (tournament == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"认证凭证无效\"}");
            return false;
        }

        request.setAttribute(DIRECTOR_ATTR, tournament);
        return true;
    }

    /** 解析认证凭证:普通接口仅接受 Authorization 头;SSE 连接(EventSource 无法自定义请求头)允许 query 参数。 */
    private String resolveAuthKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        if (isSseRequest(request)) {
            return request.getParameter("authKey");
        }
        return null;
    }

    /** SSE 端点判定:EventSource 无法设置请求头,这些路径允许 authKey 走 query。 */
    private boolean isSseRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.endsWith("/sse") || uri.endsWith("/tournament/screen/control");
    }
}
