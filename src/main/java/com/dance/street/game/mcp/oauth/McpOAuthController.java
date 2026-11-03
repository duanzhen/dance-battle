package com.dance.street.game.mcp.oauth;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端使用的最小 OAuth 2.1 发现与授权端点。
 *
 * <p>实现 authorization_code + PKCE(S256) 和 JWT 形式的 Dynamic Client Registration；
 * 授权请求先跳转前端确认页，由已登录管理员调用受保护接口完成签发。</p>
 */
@SaIgnore
@RestController
public class McpOAuthController {

    private final McpOAuthService oauthService;
    private final McpPublicUrlResolver urlResolver;

    public McpOAuthController(McpOAuthService oauthService, McpPublicUrlResolver urlResolver) {
        this.oauthService = oauthService;
        this.urlResolver = urlResolver;
    }

    @GetMapping({
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-authorization-server/mcp"
    })
    public Map<String, Object> authorizationServerMetadata(HttpServletRequest request) {
        String baseUrl = urlResolver.baseUrl(request);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", baseUrl);
        metadata.put("authorization_endpoint", baseUrl + "/authorize");
        metadata.put("token_endpoint", baseUrl + "/token");
        metadata.put("registration_endpoint", baseUrl + "/register");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("scopes_supported", List.of("mcp"));
        metadata.put("response_modes_supported", List.of("query"));
        metadata.put("authorization_response_iss_parameter_supported", true);
        metadata.put("service_documentation", baseUrl + "/mcp");
        return metadata;
    }

    @GetMapping({
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-protected-resource/mcp"
    })
    public Map<String, Object> protectedResourceMetadata(HttpServletRequest request) {
        String baseUrl = urlResolver.baseUrl(request);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", baseUrl + "/mcp");
        metadata.put("resource_name", "Dance Battle MCP");
        metadata.put("authorization_servers", List.of(baseUrl));
        metadata.put("scopes_supported", List.of("mcp"));
        metadata.put("bearer_methods_supported", List.of("header"));
        return metadata;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(oauthService.register(request, body));
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam Map<String, String> params,
                                          HttpServletRequest request) {
        UriComponentsBuilder redirect = UriComponentsBuilder
            .fromUriString(urlResolver.baseUrl(request) + "/mcp/authorize");
        params.forEach(redirect::queryParam);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, redirect.build().encode().toUriString())
            .cacheControl(CacheControl.noStore())
            .build();
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> params,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(oauthService.exchangeToken(request, params));
    }
}
