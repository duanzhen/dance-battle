package com.dance.street.game.mcp;

import cn.hutool.jwt.JWT;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.mapper.TTournamentMapper;
import org.dromara.common.json.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MCP 客户端完整接入链路：OAuth discovery → DCR → PKCE → JWT → MCP tool call。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.redis.enabled=false",
        "app.schema-init.enabled=true"
    }
)
class McpOAuthFlowTest {

    private static final String DB_PATH = "target/mcp-oauth-flow.db";
    private static final String REDIRECT_URI = "http://127.0.0.1:43127/callback";
    private static final String CODE_VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~abcd";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:sqlite:" + DB_PATH
                + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TTournamentMapper tournamentMapper;

    @Test
    void clientCanDiscoverAuthorizeAndCallTournamentTool() throws Exception {
        TTournament tournament = new TTournament();
        tournament.setName("MCP 测试赛事");
        tournament.setAuthKey("mcp-test-auth-key");
        tournamentMapper.insert(tournament);

        String baseUrl = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpResponse<String> discovery = get(client, baseUrl + "/.well-known/oauth-authorization-server");
        assertEquals(200, discovery.statusCode());
        Map<?, ?> metadata = JsonUtils.parseObject(discovery.body(), Map.class);
        assertEquals(baseUrl, metadata.get("issuer"));
        assertEquals(baseUrl + "/register", metadata.get("registration_endpoint"));
        assertEquals(baseUrl + "/token", metadata.get("token_endpoint"));
        assertTrue(((List<?>) metadata.get("grant_types_supported"))
            .containsAll(List.of("authorization_code", "refresh_token")));

        HttpResponse<String> unauthorized = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", null);
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").orElse("")
            .contains("resource_metadata="));

        Map<String, Object> registrationRequest = new LinkedHashMap<>();
        registrationRequest.put("client_name", "MCP integration test");
        registrationRequest.put("redirect_uris", List.of(REDIRECT_URI));
        registrationRequest.put("token_endpoint_auth_method", "none");
        HttpResponse<String> registration = postJson(client, baseUrl + "/register",
            JsonUtils.toJsonString(registrationRequest), null);
        assertEquals(201, registration.statusCode());
        Map<?, ?> registrationResponse = JsonUtils.parseObject(registration.body(), Map.class);
        String clientId = (String) registrationResponse.get("client_id");
        assertNotNull(clientId);
        assertEquals("mcp_client_registration", JWT.of(clientId).getPayload("token_use"));
        assertTrue(((List<?>) JWT.of(clientId).getPayload("redirect_uris")).contains(REDIRECT_URI));

        String challenge = pkceChallenge(CODE_VERIFIER);
        String authorizeUrl = UriComponentsBuilder.fromUriString(baseUrl + "/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", REDIRECT_URI)
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("scope", "mcp")
            .queryParam("resource", baseUrl + "/mcp")
            .queryParam("state", "state-123")
            .build()
            .encode()
            .toUriString();
        HttpResponse<String> authorization = get(client, authorizeUrl);
        assertEquals(302, authorization.statusCode());
        String authorizePage = authorization.headers().firstValue("Location").orElseThrow();
        assertTrue(authorizePage.startsWith(baseUrl + "/mcp/authorize?"), authorizePage);

        Map<String, Object> authorizationRequest = new LinkedHashMap<>();
        authorizationRequest.put("response_type", "code");
        authorizationRequest.put("client_id", clientId);
        authorizationRequest.put("redirect_uri", REDIRECT_URI);
        authorizationRequest.put("code_challenge", challenge);
        authorizationRequest.put("code_challenge_method", "S256");
        authorizationRequest.put("scope", "mcp");
        authorizationRequest.put("resource", baseUrl + "/mcp");
        authorizationRequest.put("state", "state-123");

        HttpResponse<String> unauthenticatedApproval = postJson(client,
            baseUrl + "/mcp/authorize/approve", JsonUtils.toJsonString(authorizationRequest), null);
        Map<?, ?> unauthenticatedResult = JsonUtils.parseObject(unauthenticatedApproval.body(), Map.class);
        assertEquals(401, ((Number) unauthenticatedResult.get("code")).intValue());

        HttpResponse<String> login = postJson(client, baseUrl + "/login",
            "{\"username\":\"admin\",\"password\":\"123456\"}", null);
        assertEquals(200, login.statusCode(), login.body());
        Map<?, ?> loginBody = JsonUtils.parseObject(login.body(), Map.class);
        String adminToken = (String) ((Map<?, ?>) loginBody.get("data")).get("token");
        assertNotNull(adminToken);

        HttpResponse<String> approval = postJson(client, baseUrl + "/mcp/authorize/approve",
            JsonUtils.toJsonString(authorizationRequest), adminToken);
        assertEquals(200, approval.statusCode(), approval.body());
        Map<?, ?> approvalBody = JsonUtils.parseObject(approval.body(), Map.class);
        String callbackUrl = (String) ((Map<?, ?>) approvalBody.get("data")).get("redirectUrl");
        assertNotNull(callbackUrl);
        String code = UriComponentsBuilder.fromUriString(callbackUrl).build()
            .getQueryParams().getFirst("code");
        assertNotNull(code);
        assertEquals("mcp_authorization_code", JWT.of(code).getPayload("token_use"));

        Map<String, String> tokenRequest = new LinkedHashMap<>();
        tokenRequest.put("grant_type", "authorization_code");
        tokenRequest.put("client_id", clientId);
        tokenRequest.put("redirect_uri", REDIRECT_URI);
        tokenRequest.put("code", code);
        tokenRequest.put("code_verifier", CODE_VERIFIER);
        tokenRequest.put("resource", baseUrl + "/mcp");
        HttpResponse<String> tokenResponse = postForm(client, baseUrl + "/token", tokenRequest);
        assertEquals(200, tokenResponse.statusCode());
        Map<?, ?> tokenBody = JsonUtils.parseObject(tokenResponse.body(), Map.class);
        String accessToken = (String) tokenBody.get("access_token");
        String refreshToken = (String) tokenBody.get("refresh_token");
        assertNotNull(accessToken);
        assertNotNull(refreshToken);
        assertEquals("mcp_access_token", JWT.of(accessToken).getPayload("token_use"));
        assertEquals("mcp_refresh_token", JWT.of(refreshToken).getPayload("token_use"));
        assertTrue(tokenLifetime(accessToken) >= 86400, "access token 默认应至少有效 24 小时");
        assertTrue(tokenLifetime(refreshToken) >= 2592000, "refresh token 默认应至少有效 30 天");

        HttpResponse<String> refreshAsBearer = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\",\"params\":{}}",
            refreshToken);
        assertEquals(401, refreshAsBearer.statusCode(), "refresh token 不得直接作为 Bearer token 使用");

        Map<String, String> refreshRequest = new LinkedHashMap<>();
        refreshRequest.put("grant_type", "refresh_token");
        refreshRequest.put("client_id", clientId);
        refreshRequest.put("refresh_token", refreshToken);
        refreshRequest.put("resource", baseUrl + "/mcp");
        HttpResponse<String> refreshResponse = postForm(client, baseUrl + "/token", refreshRequest);
        assertEquals(200, refreshResponse.statusCode(), refreshResponse.body());
        Map<?, ?> refreshBody = JsonUtils.parseObject(refreshResponse.body(), Map.class);
        String refreshedAccessToken = (String) refreshBody.get("access_token");
        String refreshedRefreshToken = (String) refreshBody.get("refresh_token");
        assertNotNull(refreshedAccessToken);
        assertNotNull(refreshedRefreshToken);
        assertTrue(!accessToken.equals(refreshedAccessToken), "刷新后应签发新的 access token");
        assertTrue(!refreshToken.equals(refreshedRefreshToken), "刷新后应签发新的 refresh token");
        assertEquals("mcp_access_token", JWT.of(refreshedAccessToken).getPayload("token_use"));
        assertEquals("mcp_refresh_token", JWT.of(refreshedRefreshToken).getPayload("token_use"));
        accessToken = refreshedAccessToken;

        HttpResponse<String> initialize = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"integration-test\",\"version\":\"1.0.0\"}}}",
            accessToken);
        assertEquals(200, initialize.statusCode(), initialize.body());
        assertTrue(initialize.body().contains("MUST first call prompts/get"),
            "MCP 初始化说明必须要求 Agent 先加载业务 Prompt");
        assertTrue(initialize.body().contains("reuse the resulting context"),
            "MCP 初始化说明必须要求后续工具调用复用业务 Prompt 上下文");

        HttpResponse<String> tools = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", accessToken);
        assertEquals(200, tools.statusCode(), tools.body());
        for (String toolName : List.of(
            "list_tournaments", "manage_tournaments", "manage_stages", "manage_rosters",
            "manage_players", "manage_competitors", "manage_referees", "manage_matches",
            "manage_displays"
        )) {
            assertTrue(tools.body().contains(toolName), tools.body());
        }

        HttpResponse<String> call = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"list_tournaments\",\"arguments\":{\"limit\":5}}}",
            accessToken);
        assertEquals(200, call.statusCode(), call.body());
        assertTrue(call.body().contains("MCP 测试赛事"), call.body());

        HttpResponse<String> managementCall = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"manage_tournaments\",\"arguments\":{\"action\":\"get\",\"id\":"
                + tournament.getId() + "}}}",
            accessToken);
        assertEquals(200, managementCall.statusCode(), managementCall.body());
        assertTrue(managementCall.body().contains("MCP 测试赛事"), managementCall.body());

        HttpResponse<String> managementWrite = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"manage_tournaments\",\"arguments\":{\"action\":\"create_from_template\","
                + "\"noRefereeConfirmed\":true,\"data\":{"
                + "\"name\":\"MCP 写入测试\",\"templateCode\":\"AUDITION_16\","
                + "\"logicalWidth\":1920,\"logicalHeight\":1080}}}}",
            accessToken);
        assertEquals(200, managementWrite.statusCode(), managementWrite.body());
        assertTrue(managementWrite.body().contains("MCP 写入测试"), managementWrite.body());

        HttpResponse<String> prompts = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/list\",\"params\":{}}",
            accessToken);
        assertEquals(200, prompts.statusCode(), prompts.body());
        assertTrue(prompts.body().contains("dance_battle_operator"), prompts.body());

        HttpResponse<String> prompt = postJson(client, baseUrl + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/get\",\"params\":{"
                + "\"name\":\"dance_battle_operator\",\"arguments\":{"
                + "\"focus\":\"roster\",\"tournamentId\":\""
                + tournament.getId() + "\"}}}",
            accessToken);
        assertEquals(200, prompt.statusCode(), prompt.body());
        assertTrue(prompt.body().contains("当前重点：名单"), prompt.body());
        assertTrue(prompt.body().contains("manage_rosters"), prompt.body());
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(HttpClient client, String url,
                                                 String body, String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(HttpClient client, String url,
                                                 Map<String, String> values) throws Exception {
        String body = values.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String pkceChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static long tokenLifetime(String token) {
        JWT jwt = JWT.of(token);
        return ((Number) jwt.getPayload("exp")).longValue()
            - ((Number) jwt.getPayload("iat")).longValue();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
