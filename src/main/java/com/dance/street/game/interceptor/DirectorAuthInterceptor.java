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
 * <p>仅接受 {@code Authorization: Bearer <authKey>} 请求头,不使用 query 参数,
 * 避免 authKey 出现在 URL/访问日志。/game/director/** 下无 SSE 端点,无需 query 兜底。</p>
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

    /** 认证凭证仅从 Authorization 头解析,不使用 query 参数 */
    private String resolveAuthKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
