package com.dance.street.game.mcp.oauth;

import cn.dev33.satoken.SaManager;
import cn.hutool.core.convert.Convert;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 为 MCP OAuth 流程签发的无状态 JWT。
 *
 * <p>这是演示用的最小实现：token 可以验证签名、issuer、audience、有效期和令牌用途，
 * 但不实现 refresh token、撤销列表或 scope 授权服务。</p>
 */
@Service
public class McpJwtService {

    private static final String CLIENT_TOKEN_USE = "mcp_client_registration";
    private static final String AUTH_CODE_TOKEN_USE = "mcp_authorization_code";
    private static final String ACCESS_TOKEN_USE = "mcp_access_token";

    private final McpPublicUrlResolver urlResolver;

    @Value("${mcp.oauth.access-token-ttl-seconds:3600}")
    private long accessTokenTtlSeconds;

    @Value("${mcp.oauth.client-registration-ttl-seconds:2592000}")
    private long clientRegistrationTtlSeconds;

    @Value("${mcp.oauth.authorization-code-ttl-seconds:300}")
    private long authorizationCodeTtlSeconds;

    public McpJwtService(McpPublicUrlResolver urlResolver) {
        this.urlResolver = urlResolver;
    }

    public String issueClientRegistration(HttpServletRequest request, String clientName,
                                          List<String> redirectUris, String authMethod) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", urlResolver.baseUrl(request));
        payload.put("sub", "mcp-client");
        payload.put("aud", urlResolver.baseUrl(request));
        payload.put("client_name", clientName);
        payload.put("redirect_uris", redirectUris);
        payload.put("token_endpoint_auth_method", authMethod);
        payload.put("token_use", CLIENT_TOKEN_USE);
        payload.put("iat", now);
        payload.put("exp", now + Math.max(300, clientRegistrationTtlSeconds));
        payload.put("jti", UUID.randomUUID().toString());
        return JWTUtil.createToken(payload, signer());
    }

    public Optional<RegisteredClientClaims> verifyClientRegistration(HttpServletRequest request, String clientId) {
        try {
            JWT jwt = verifiedJwt(request, clientId, CLIENT_TOKEN_USE);
            List<String> redirectUris = stringList(jwt.getPayload("redirect_uris"));
            if (redirectUris.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RegisteredClientClaims(
                Convert.toStr(jwt.getPayload("client_name")),
                redirectUris,
                Convert.toStr(jwt.getPayload("token_endpoint_auth_method")),
                Convert.toLong(jwt.getPayload("iat"))
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public String issueAuthorizationCode(HttpServletRequest request, String clientId,
                                         String redirectUri, String codeChallenge,
                                         String scope, String resource) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", urlResolver.baseUrl(request));
        payload.put("aud", resource);
        payload.put("client_id_hash", sha256Base64Url(clientId));
        payload.put("redirect_uri", redirectUri);
        payload.put("code_challenge", codeChallenge);
        payload.put("scope", scope);
        payload.put("token_use", AUTH_CODE_TOKEN_USE);
        payload.put("iat", now);
        payload.put("exp", now + Math.max(30, authorizationCodeTtlSeconds));
        payload.put("jti", UUID.randomUUID().toString());
        return JWTUtil.createToken(payload, signer());
    }

    public Optional<AuthorizationCodeClaims> verifyAuthorizationCode(HttpServletRequest request, String code) {
        try {
            JWT jwt = verifiedJwt(request, code, AUTH_CODE_TOKEN_USE);
            return Optional.of(new AuthorizationCodeClaims(
                Convert.toStr(jwt.getPayload("client_id_hash")),
                Convert.toStr(jwt.getPayload("redirect_uri")),
                Convert.toStr(jwt.getPayload("code_challenge")),
                Convert.toStr(jwt.getPayload("scope")),
                Convert.toStr(jwt.getPayload("aud"))
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public String issueAccessToken(HttpServletRequest request, String clientId, String scope, String resource) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", urlResolver.baseUrl(request));
        payload.put("sub", "mcp-agent");
        payload.put("aud", resource);
        payload.put("client_id", clientId);
        payload.put("scope", scope);
        payload.put("token_use", ACCESS_TOKEN_USE);
        payload.put("iat", now);
        payload.put("exp", now + Math.max(60, accessTokenTtlSeconds));
        payload.put("jti", UUID.randomUUID().toString());
        return JWTUtil.createToken(payload, signer());
    }

    public Optional<McpTokenClaims> verify(HttpServletRequest request, String token) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        try {
            JWTSigner signer = signer();
            if (!JWTUtil.verify(token, signer)) {
                return Optional.empty();
            }
            JWT jwt = JWT.of(token);
            JWTValidator.of(jwt).validateDate();
            if (!ACCESS_TOKEN_USE.equals(Convert.toStr(jwt.getPayload("token_use")))
                || !urlResolver.baseUrl(request).equals(Convert.toStr(jwt.getPayload("iss")))
                || !urlResolver.mcpResource(request).equals(Convert.toStr(jwt.getPayload("aud")))) {
                return Optional.empty();
            }
            return Optional.of(new McpTokenClaims(
                Convert.toStr(jwt.getPayload("sub")),
                Convert.toStr(jwt.getPayload("client_id")),
                Convert.toStr(jwt.getPayload("scope")),
                Convert.toStr(jwt.getPayload("jti"))
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return Math.max(60, accessTokenTtlSeconds);
    }

    private JWT verifiedJwt(HttpServletRequest request, String token, String expectedUse) {
        if (StringUtils.isBlank(token)) {
            throw new IllegalArgumentException("token is blank");
        }
        if (!JWTUtil.verify(token, signer())) {
            throw new IllegalArgumentException("invalid signature");
        }
        JWT jwt = JWT.of(token);
        JWTValidator.of(jwt).validateDate();
        if (!expectedUse.equals(Convert.toStr(jwt.getPayload("token_use")))
            || !urlResolver.baseUrl(request).equals(Convert.toStr(jwt.getPayload("iss")))) {
            throw new IllegalArgumentException("invalid token claims");
        }
        return jwt;
    }

    private JWTSigner signer() {
        String secret = SaManager.getConfig().getJwtSecretKey();
        if (StringUtils.isBlank(secret)) {
            throw new IllegalStateException("JWT 签名密钥尚未初始化");
        }
        return JWTSignerUtil.hs256(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .toList();
    }

    public record McpTokenClaims(String subject, String clientId, String scope, String tokenId) {
    }

    public record RegisteredClientClaims(String name, List<String> redirectUris,
                                         String tokenEndpointAuthMethod, long issuedAt) {
    }

    public record AuthorizationCodeClaims(String clientIdHash, String redirectUri,
                                          String codeChallenge, String scope, String resource) {
    }
}
