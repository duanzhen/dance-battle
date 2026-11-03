package com.dance.street.game.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBusinessPromptTest {

    private final McpBusinessPrompt prompt = new McpBusinessPrompt();

    @Test
    void exposesBusinessGuideWithOptionalContextArguments() {
        assertEquals(McpBusinessPrompt.PROMPT_NAME, prompt.definition().name());
        assertEquals(4, prompt.definition().arguments().size());
        assertTrue(prompt.definition().arguments().stream()
            .noneMatch(McpSchema.PromptArgument::required));
    }

    @Test
    void rendersFocusAndCurrentBusinessContext() {
        var result = prompt.generate(McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "focus", "roster",
                "tournamentId", "100",
                "stageId", "200",
                "matchId", "300"
            )));

        String text = ((McpSchema.TextContent) result.messages().getFirst().content()).text();
        assertTrue(text.contains("tournamentId：100"));
        assertTrue(text.contains("stageId：200"));
        assertTrue(text.contains("具体用户目标由 Agent 根据当前对话理解"));
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
        assertTrue(text.contains("clarification_required"));
        assertTrue(text.contains("禁止用临时名称"));
        assertTrue(text.contains("BLANK、AUDITION_32"));
        assertTrue(text.contains("create_from_template"));
        assertTrue(text.contains("noRefereeConfirmed=true"));
        assertFalse(text.contains("create_blank"));
        assertTrue(text.contains("afterStageId"));
        assertTrue(text.contains("teamCountStart"));
        assertTrue(text.contains("teamCountEnd"));
        assertTrue(text.contains("AUDITION 默认自动创建 1 个圈"));
        assertTrue(text.contains("add_circle"));
        assertTrue(text.contains("activeTournamentId"));
        assertTrue(text.contains("默认都指这个刚创建的赛事"));
        assertTrue(text.contains("clarification_required、isError=true 或业务失败不会更新上下文"));
        assertTrue(text.contains("players=[{name,idCard?,tags?,remark?}]"));
        assertTrue(text.contains("执行常规赛事操作时无需搜索项目源码"));
        assertTrue(text.contains("Player 与 Competitor 是两个领域"));
        assertTrue(text.contains("manage_competitors.register_player"));
    }

    @Test
    void allowsEmptyArgumentsAndRejectsUnknownFocus() {
        var result = prompt.generate(McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of()));
        assertTrue(((McpSchema.TextContent) result.messages().getFirst().content()).text()
            .contains("具体用户目标由 Agent 根据当前对话理解"));
        assertThrows(ServiceException.class, () -> prompt.generate(
            McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "focus", "unknown"
            ))));
    }

    @Test
    void setupGuideShowsAllTemplateLabelsIncludingBlank() {
        var result = prompt.generate(McpTransportContext.EMPTY,
            new McpSchema.GetPromptRequest(McpBusinessPrompt.PROMPT_NAME, Map.of(
                "focus", "setup"
            )));
        String text = ((McpSchema.TextContent) result.messages().getFirst().content()).text();
        assertTrue(text.contains("BLANK（空白赛事）"));
        assertTrue(text.contains("AUDITION_32（海选32人）"));
        assertTrue(text.contains("AUDITION_16（海选16人）"));
        assertTrue(text.contains("AUDITION_ARENA（海选32SevenToSmoke）"));
    }
}
