package com.dance.street.game.mcp.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 最小 OAuth 2.1 授权码服务。
 *
 * <p>client_id 与 authorization code 本身就是签名 JWT，注册信息和授权信息直接编码在
 * token 中，服务端不保存注册表或授权码表。授权码只能由已登录管理员通过确认接口签发；
 * 不实现 client secret、撤销列表和授权码一次性消费。</p>
 */
@Service
public class McpOAuthService {

    private static final String DEFAULT_SCOPE = "mcp";
    private static final Set<String> SUPPORTED_AUTH_METHODS = Set.of("none");

    private final McpJwtService jwtService;
    private final McpPublicUrlResolver urlResolver;

    public McpOAuthService(McpJwtService jwtService, McpPublicUrlResolver urlResolver) {
        this.jwtService = jwtService;
        this.urlResolver = urlResolver;
    }

    public Map<String, Object> register(HttpServletRequest request, Map<String, Object> body) {
        List<String> redirectUris = stringList(body.get("redirect_uris"));
        if (redirectUris.isEmpty()) {
            throw McpOAuthException.invalidRequest("redirect_uris is required");
        }
        redirectUris.forEach(this::validateRedirectUri);

        String requestedMethod = (String) body.get("token_endpoint_auth_method");
        String authMethod = StringUtils.blankToDefault(StringUtils.trim(requestedMethod), "none");
        if (!SUPPORTED_AUTH_METHODS.contains(authMethod)) {
            throw McpOAuthException.invalidRequest("only token_endpoint_auth_method=none is supported");
        }

        long issuedAt = Instant.now().getEpochSecond();
        String clientName = StringUtils.blankToDefault((String) body.get("client_name"), "MCP Client");
        String clientId = jwtService.issueClientRegistration(
            request, clientName, List.copyOf(redirectUris), authMethod);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", issuedAt);
        response.put("client_name", clientName);
        response.put("redirect_uris", redirectUris);
        response.put("grant_types", List.of("authorization_code", "refresh_token"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        return response;
    }

    public String authorize(HttpServletRequest request, Map<String, String> params) {
        String clientId = require(params, "client_id");
        McpJwtService.RegisteredClientClaims client = jwtService
            .verifyClientRegistration(request, clientId)
            .orElse(null);
        if (client == null) {
            throw McpOAuthException.invalidClient("unknown client_id");
        }
        if (!"code".equals(params.get("response_type"))) {
            throw McpOAuthException.invalidRequest("response_type must be code");
        }
        String redirectUri = require(params, "redirect_uri");
        if (!client.redirectUris().contains(redirectUri)) {
            throw McpOAuthException.invalidRequest("redirect_uri was not registered for this client");
        }
        if (!"S256".equals(params.get("code_challenge_method"))) {
            throw McpOAuthException.invalidRequest("code_challenge_method must be S256");
        }
        String challenge = require(params, "code_challenge");
        if (challenge.length() < 43 || challenge.length() > 128) {
            throw McpOAuthException.invalidRequest("invalid code_challenge");
        }

        String scope = normalizeScope(params.get("scope"));
        String resource = normalizeResource(request, params.get("resource"));
        return jwtService.issueAuthorizationCode(
            request, clientId, redirectUri, challenge, scope, resource);
    }

    public Map<String, Object> exchangeToken(HttpServletRequest request, Map<String, String> params) {
        return switch (params.get("grant_type")) {
            case "authorization_code" -> exchangeAuthorizationCode(request, params);
            case "refresh_token" -> exchangeRefreshToken(request, params);
            default -> throw new McpOAuthException("unsupported_grant_type",
                "only authorization_code and refresh_token are supported", HttpStatus.BAD_REQUEST);
        };
    }

    private Map<String, Object> exchangeAuthorizationCode(HttpServletRequest request,
                                                           Map<String, String> params) {
        String codeValue = require(params, "code");
        McpJwtService.AuthorizationCodeClaims code = jwtService
            .verifyAuthorizationCode(request, codeValue)
            .orElse(null);
        if (code == null) {
            throw McpOAuthException.invalidGrant("authorization code is invalid or expired");
        }
        String clientId = require(params, "client_id");
        McpJwtService.RegisteredClientClaims client = jwtService
            .verifyClientRegistration(request, clientId)
            .orElse(null);
        if (client == null || !MessageDigest.isEqual(
            code.clientIdHash().getBytes(StandardCharsets.US_ASCII),
            sha256Base64Url(clientId).getBytes(StandardCharsets.US_ASCII))) {
            throw McpOAuthException.invalidClient("client_id does not match authorization code");
        }
        String redirectUri = require(params, "redirect_uri");
        if (!code.redirectUri().equals(redirectUri) || !client.redirectUris().contains(redirectUri)) {
            throw McpOAuthException.invalidGrant("redirect_uri does not match authorization code");
        }
        String verifier = require(params, "code_verifier");
        if (!MessageDigest.isEqual(
            code.codeChallenge().getBytes(StandardCharsets.US_ASCII),
            sha256Base64Url(verifier).getBytes(StandardCharsets.US_ASCII))) {
            throw McpOAuthException.invalidGrant("PKCE verification failed");
        }
        if (StringUtils.isNotBlank(params.get("resource"))
            && !code.resource().equals(params.get("resource").trim())) {
            throw McpOAuthException.invalidGrant("resource does not match authorization code");
        }

        return tokenResponse(request, clientId, code.scope(), code.resource());
    }

    private Map<String, Object> exchangeRefreshToken(HttpServletRequest request,
                                                      Map<String, String> params) {
        String refreshToken = require(params, "refresh_token");
        String clientId = require(params, "client_id");
        McpJwtService.RegisteredClientClaims client = jwtService
            .verifyClientRegistration(request, clientId)
            .orElse(null);
        McpJwtService.McpTokenClaims claims = jwtService
            .verifyRefreshToken(request, refreshToken)
            .orElse(null);
        if (client == null || (claims != null && !clientId.equals(claims.clientId()))) {
            throw McpOAuthException.invalidClient("client_id does not match refresh token");
        }
        if (claims == null) {
            throw McpOAuthException.invalidGrant("refresh token is invalid or expired");
        }
        String scope = normalizeScope(params.get("scope"));
        if (!scope.equals(claims.scope())) {
            throw new McpOAuthException("invalid_scope",
                "the requested scope exceeds the original grant", HttpStatus.BAD_REQUEST);
        }
        String requestedResource = params.get("resource");
        if (StringUtils.isNotBlank(requestedResource)
            && !urlResolver.mcpResource(request).equals(requestedResource.trim())) {
            throw McpOAuthException.invalidGrant("resource does not match this MCP server");
        }
        return tokenResponse(request, clientId, claims.scope(), urlResolver.mcpResource(request));
    }

    private Map<String, Object> tokenResponse(HttpServletRequest request, String clientId,
                                              String scope, String resource) {
        String accessToken = jwtService.issueAccessToken(request, clientId, scope, resource);
        String refreshToken = jwtService.issueRefreshToken(request, clientId, scope, resource);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", jwtService.accessTokenTtlSeconds());
        response.put("refresh_token", refreshToken);
        response.put("refresh_token_expires_in", jwtService.refreshTokenTtlSeconds());
        response.put("scope", scope);
        return response;
    }

    private String normalizeResource(HttpServletRequest request, String resource) {
        String expected = urlResolver.mcpResource(request);
        if (StringUtils.isBlank(resource)) {
            return expected;
        }
        if (!expected.equals(resource.trim())) {
            throw McpOAuthException.invalidRequest("resource does not match this MCP server");
        }
        return expected;
    }

    private static String normalizeScope(String scope) {
        if (StringUtils.isBlank(scope)) {
            return DEFAULT_SCOPE;
        }
        for (String item : scope.trim().split("\\s+")) {
            if (!DEFAULT_SCOPE.equals(item)) {
                throw McpOAuthException.invalidRequest("unsupported scope: " + item);
            }
        }
        return DEFAULT_SCOPE;
    }

    private static String require(Map<String, String> params, String name) {
        String value = params.get(name);
        if (StringUtils.isBlank(value)) {
            throw McpOAuthException.invalidRequest(name + " is required");
        }
        return value.trim();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String text && StringUtils.isNotBlank(text)) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private void validateRedirectUri(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            if (uri.getScheme() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || StringUtils.isBlank(uri.getHost())) {
                throw McpOAuthException.invalidRequest("redirect_uri must be an absolute http(s) URI");
            }
        } catch (IllegalArgumentException e) {
            throw McpOAuthException.invalidRequest("invalid redirect_uri");
        }
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
