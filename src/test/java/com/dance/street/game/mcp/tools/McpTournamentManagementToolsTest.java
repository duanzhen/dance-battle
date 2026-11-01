package com.dance.street.game.mcp.tools;

import com.dance.street.game.mcp.oauth.McpJwtService.McpTokenClaims;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.ToolInputValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=false",
    "spring.datasource.url=jdbc:sqlite:target/mcp-tournament-management-tools-test.db"
})
class McpTournamentManagementToolsTest {

    private final McpTournamentManagementTools tools = new McpTournamentManagementTools(null);

    @Test
    void templateCreateRejectsPlaceholderNameAndMissingRefereeDecision() {
        Map<String, Object> placeholder = Map.of(
            "name", "新赛事 2026-11-01",
            "templateCode", "AUDITION_32",
            "logicalWidth", 1920,
            "logicalHeight", 1080,
            "refereeNames", List.of("裁判A")
        );
        var placeholderResult = call("create_from_template", placeholder);
        assertTrue(placeholderResult.isError());
        assertTrue(text(placeholderResult).contains("占位赛事名称"));

        Map<String, Object> noRefereeDecision = Map.of(
            "name", "2026 城市街舞赛",
            "templateCode", "AUDITION_32",
            "logicalWidth", 1920,
            "logicalHeight", 1080
        );
        var refereeResult = call("create_from_template", noRefereeDecision);
        assertTrue(refereeResult.isError());
        assertTrue(text(refereeResult).contains("noRefereeConfirmed"));

        Map<String, Object> duplicateReferees = Map.of(
            "name", "2026 城市街舞赛",
            "templateCode", "AUDITION_32",
            "logicalWidth", 1920,
            "logicalHeight", 1080,
            "refereeNames", List.of("裁判A", "裁判A")
        );
        var duplicateResult = call("create_from_template", duplicateReferees);
        assertTrue(duplicateResult.isError());
        assertTrue(text(duplicateResult).contains("裁判姓名不能重复"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaExposesTemplateRequirements() {
        McpSchema.Tool tool = tools.definition();
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        Map<String, Object> data = (Map<String, Object>) properties.get("data");
        Map<String, Object> dataProperties = (Map<String, Object>) data.get("properties");
        Map<String, Object> templateCode = (Map<String, Object>) dataProperties.get("templateCode");

        assertEquals(4, ((List<?>) templateCode.get("enum")).size());
        assertTrue(((List<?>) templateCode.get("enum")).containsAll(
            List.of("BLANK", "AUDITION_32", "AUDITION_16", "AUDITION_ARENA")));
        String description = String.valueOf(templateCode.get("description"));
        assertTrue(description.contains("BLANK（空白赛事）"));
        assertTrue(description.contains("AUDITION_32（海选32人）"));
        assertTrue(description.contains("AUDITION_16（海选16人）"));
        assertTrue(description.contains("AUDITION_ARENA（海选32SevenToSmoke）"));
        Map<String, Object> action = (Map<String, Object>) properties.get("action");
        assertFalse(((List<?>) action.get("enum")).contains("create"));
        assertFalse(((List<?>) tool.inputSchema().get("allOf")).isEmpty());
        assertTrue(properties.containsKey("noRefereeConfirmed"));
    }

    @Test
    void sdkInputValidatorRejectsTemplateCreateWithoutRequiredFields() {
        McpSchema.Tool tool = tools.definition();
        var validator = new DefaultJsonSchemaValidator(
            new com.fasterxml.jackson.databind.ObjectMapper());
        Map<String, Object> incomplete = Map.of(
            "action", "create_from_template",
            "noRefereeConfirmed", true,
            "data", Map.of(
                "name", "2026 城市街舞赛",
                "logicalWidth", 1920,
                "logicalHeight", 1080
            )
        );

        assertNotNull(ToolInputValidator.validate(tool, incomplete, true, validator));

        Map<String, Object> complete = Map.of(
            "action", "create_from_template",
            "noRefereeConfirmed", true,
            "data", Map.of(
                "name", "2026 城市街舞赛",
                "templateCode", "AUDITION_32"
            )
        );
        assertNull(ToolInputValidator.validate(tool, complete, true, validator));
    }

    private McpSchema.CallToolResult call(String action, Map<String, Object> data) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", action);
        arguments.put("data", data);
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
            .name("manage_tournaments")
            .arguments(arguments)
            .build();
        return tools.execute(context(), request);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static McpTransportContext context() {
        return McpTransportContext.create(Map.of(
            "claims", new McpTokenClaims("mcp-agent", "test-client", "mcp", "token-1")
        ));
    }
}
