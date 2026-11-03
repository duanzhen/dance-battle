package com.dance.street.game.mcp.tools;

import com.dance.street.game.mcp.oauth.McpJwtService.McpTokenClaims;
import io.modelcontextprotocol.common.McpTransportContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolSupportTest {

    @Test
    void acceptsAuthenticatedMcpAgentClaims() {
        String tokenId = McpToolSupport.authorize(context(
            new McpTokenClaims("mcp-agent", "client", "mcp", "token-1")));

        assertEquals("token-1", tokenId);
    }

    @Test
    void rejectsMissingOrInsufficientClaims() {
        assertThrows(ServiceException.class,
            () -> McpToolSupport.authorize(McpTransportContext.EMPTY));
        assertThrows(ServiceException.class,
            () -> McpToolSupport.authorize(context(
                new McpTokenClaims("mcp-agent", "client", "read", "token-1"))));
        assertThrows(ServiceException.class,
            () -> McpToolSupport.authorize(context(
                new McpTokenClaims("someone-else", "client", "mcp", "token-1"))));
    }

    @Test
    void parsesLongListsAndCapsPagination() {
        Map<String, Object> arguments = Map.of(
            "ids", List.of(1, "2", 3L),
            "pageNum", 0,
            "pageSize", 1000
        );

        assertEquals(List.of(1L, 2L, 3L), McpToolSupport.longList(arguments, "ids"));
        var page = McpToolSupport.pageQuery(arguments);
        assertEquals(1, page.getPageNum());
        assertEquals(100, page.getPageSize());
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemasAllowArrayImportAndNullableCurrentCompetitor() {
        var playerProperties = (Map<String, Object>) new McpPlayerTools(null).definition()
            .inputSchema().get("properties");
        Map<String, Object> playerData = (Map<String, Object>) playerProperties.get("data");
        assertEquals(List.of("object", "array"),
            ((List<Map<String, Object>>) playerData.get("anyOf")).stream()
                .map(item -> item.get("type")).toList());

        var matchProperties = (Map<String, Object>) new McpMatchTools(
            null, null, null, null, null).definition().inputSchema().get("properties");
        Map<String, Object> currentCompetitor =
            (Map<String, Object>) matchProperties.get("currentCompetitorId");
        assertTrue(((List<Map<String, Object>>) currentCompetitor.get("anyOf")).stream()
            .anyMatch(item -> "null".equals(item.get("type"))));
    }

    private static McpTransportContext context(McpTokenClaims claims) {
        return McpTransportContext.create(Map.of("claims", claims));
    }
}
