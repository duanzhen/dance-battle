package org.dromara.common.file.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 上传文件访问安全头:
 * 禁止内容嗅探(nosniff)、禁止脚本化解释(CSP)、禁用缓存,
 * 防止上传目录被当作可执行/脚本资源利用。
 */
@Component
public class UploadSecurityFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/uploads/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        chain.doFilter(request, response);
    }
}
