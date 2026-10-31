package com.dance.street.game.mcp.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP/OAuth 对外 URL 解析。
 *
 * <p>生产环境建议显式设置 {@code MCP_PUBLIC_BASE_URL}，避免反向代理重写 Host 后
 * OAuth 元数据中的 issuer、endpoint 和 resource 与实际访问地址不一致。</p>
 */
@Component
public class McpPublicUrlResolver {

    @Value("${mcp.public-base-url:}")
    private String configuredBaseUrl;

    public String baseUrl(HttpServletRequest request) {
        if (StringUtils.isNotBlank(configuredBaseUrl)) {
            return stripTrailingSlash(configuredBaseUrl.trim());
        }
        String scheme = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (StringUtils.isBlank(scheme)) {
            scheme = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (StringUtils.isBlank(host)) {
            host = request.getHeader("Host");
        }
        if (StringUtils.isBlank(host)) {
            host = request.getServerName();
            int port = request.getServerPort();
            boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
            if (!defaultPort) {
                host = host + ":" + port;
            }
        }
        return scheme + "://" + host;
    }

    public String mcpResource(HttpServletRequest request) {
        return baseUrl(request) + "/mcp";
    }

    public String protectedResourceMetadataUrl(HttpServletRequest request) {
        return baseUrl(request) + "/.well-known/oauth-protected-resource/mcp";
    }

    private static String firstHeaderValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
