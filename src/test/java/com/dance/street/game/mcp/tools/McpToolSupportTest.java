package com.dance.street.game.mcp.tools;

import com.dance.street.game.mcp.oauth.McpJwtService.McpTokenClaims;
import io.modelcontextprotocol.common.McpTransportContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static McpTransportContext context(McpTokenClaims claims) {
        return McpTransportContext.create(Map.of("claims", claims));
    }
}
