package com.dance.street.game.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBusinessPromptTest {

    private final McpBusinessPrompt prompt = new McpBusinessPrompt();

    @Test
    void exposesBusinessGuideWithOptionalContextArguments() {
        assertEquals(McpBusinessPrompt.PROMPT_NAME, prompt.definition().name());
        assertEquals(5, prompt.definition().arguments().size());
        assertTrue(prompt.definition().arguments().stream()
            .anyMatch(argument -> "goal".equals(argument.name()) && argument.required()));
    }

    @Test
    void rendersFocusAndCurrentBusinessContext() {
        var result = prompt.generate(McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "goal", "检查下一赛段为什么不能开始",
                "focus", "roster",
                "tournamentId", "100",
                "stageId", "200",
                "matchId", "300"
            )));

        String text = ((McpSchema.TextContent) result.messages().getFirst().content()).text();
        assertTrue(text.contains("检查下一赛段为什么不能开始"));
        assertTrue(text.contains("tournamentId：100"));
        assertTrue(text.contains("stageId：200"));
        assertTrue(text.contains("当前重点：名单"));
        assertTrue(text.contains("DRAFT → GAMING → SETTLED"));
        assertTrue(text.contains("manage_rosters"));
        assertTrue(text.contains("AUDITION（海选）"));
        assertTrue(text.contains("KNOCKOUT（淘汰赛）"));
        assertTrue(text.contains("ARENA（擂台赛）"));
        assertTrue(text.contains("RANK（排名赛）"));
        assertTrue(text.contains("FREE_MATCH（自由对抗）"));
        assertTrue(text.contains("designate_tiebreak_advance"));
        assertTrue(text.contains("temp_withdraw_competitor"));
        assertTrue(text.contains("select_free_advancers"));
        assertTrue(text.contains("名单 DAG"));
        assertTrue(text.contains("sourceStageId → targetStageId"));
        assertTrue(text.contains("分支"));
        assertTrue(text.contains("汇合"));
        assertTrue(text.contains("rosterApplied=1"));
        assertTrue(text.contains("待落位区"));
    }

    @Test
    void rejectsMissingGoalAndUnknownFocus() {
        assertThrows(ServiceException.class, () -> prompt.generate(
            McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of())));
        assertThrows(ServiceException.class, () -> prompt.generate(
            McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "goal", "test",
                "focus", "unknown"
            ))));
    }

    @Test
    void setupGuideShowsAllTemplateLabelsIncludingBlank() {
        var result = prompt.generate(McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "goal", "创建赛事",
                "focus", "setup"
            )));
        String text = ((McpSchema.TextContent) result.messages().getFirst().content()).text();
        assertTrue(text.contains("BLANK（空白赛事）"));
        assertTrue(text.contains("AUDITION_32（海选32人）"));
        assertTrue(text.contains("AUDITION_16（海选16人）"));
        assertTrue(text.contains("AUDITION_ARENA（海选32SevenToSmoke）"));
    }
}
