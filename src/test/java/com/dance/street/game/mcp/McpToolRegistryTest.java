package com.dance.street.game.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRegistryTest {

    @Test
    void allToolBeansBecomeMcpSpecifications() {
        IMcpTool alpha = fixedTool("alpha");
        IMcpTool beta = fixedTool("beta");

        McpToolRegistry registry = new McpToolRegistry(List.of(beta, alpha));

        assertEquals(2, registry.specifications().size());
        assertTrue(registry.specifications().stream()
            .map(spec -> spec.tool().name())
            .toList()
            .containsAll(List.of("alpha", "beta")));
    }

    @Test
    void duplicateToolNamesFailAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new McpToolRegistry(List.of(fixedTool("duplicate"), fixedTool("duplicate"))));

        assertTrue(error.getMessage().contains("duplicate"));
    }

    private static IMcpTool fixedTool(String name) {
        return new IMcpTool() {
            @Override
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder(name,
                        Map.of("type", "object", "additionalProperties", false))
                    .description("Test tool " + name)
                    .build();
            }

            @Override
            public McpSchema.CallToolResult execute(McpTransportContext transportContext,
                                                    McpSchema.CallToolRequest request) {
                return McpSchema.CallToolResult.builder()
                    .addTextContent(name)
                    .build();
            }
        };
    }
}
