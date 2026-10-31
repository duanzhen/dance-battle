package com.dance.street.game.mcp.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 保护 MCP Streamable HTTP 入口，并在 401 响应中发布 OAuth Protected Resource Metadata 地址。
 */
public class McpBearerAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_CLAIMS_ATTR = McpBearerAuthFilter.class.getName() + ".claims";
    private static final String BEARER_PREFIX = "Bearer ";

    private final McpJwtService jwtService;
    private final McpPublicUrlResolver urlResolver;

    public McpBearerAuthFilter(McpJwtService jwtService, McpPublicUrlResolver urlResolver) {
        this.jwtService = jwtService;
        this.urlResolver = urlResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith(BEARER_PREFIX)
            ? authorization.substring(BEARER_PREFIX.length()).trim() : null;
        var claims = jwtService.verify(request, token);
        if (claims.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"" + urlResolver.protectedResourceMetadataUrl(request) + "\", scope=\"mcp\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                "{\"error\":\"invalid_token\",\"error_description\":\"A valid MCP Bearer token is required\"}");
            return;
        }
        request.setAttribute(TOKEN_CLAIMS_ATTR, claims.get());
        filterChain.doFilter(request, response);
    }
}
