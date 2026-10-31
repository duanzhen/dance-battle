package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITTournamentService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 赛事列表 MCP 工具。
 */
@Service
public class McpTournamentTools implements IMcpTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ITTournamentService tournamentService;

    public McpTournamentTools(ITTournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @Override
    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder("list_tournaments",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "limit", Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "maximum", MAX_LIMIT,
                            "default", DEFAULT_LIMIT,
                            "description", "Maximum number of tournaments to return."
                        )
                    ),
                    "additionalProperties", false
                ))
            .title("List tournaments")
            .description("List dance-battle tournaments with id, name, status, creation time and stage names.")
            .annotations(McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .idempotentHint(true)
                .openWorldHint(false)
                .build())
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute(McpTransportContext transportContext,
                                            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        int limit = parseLimit(arguments == null ? null : arguments.get("limit"));
        PageQuery pageQuery = new PageQuery(limit, 1);
        TableDataInfo<TTournamentVo> page = tournamentService.queryPageList(new TTournamentBo(), pageQuery);
        List<Map<String, Object>> tournaments = page.getData() == null ? List.of() : page.getData().stream()
            .map(item -> {
                Map<String, Object> compact = new LinkedHashMap<>();
                compact.put("id", item.getId());
                compact.put("name", item.getName());
                compact.put("status", item.getStatus());
                compact.put("createTime", item.getCreateTime());
                compact.put("stageNames", item.getStageNames());
                return compact;
            })
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("returned", tournaments.size());
        result.put("tournaments", tournaments);
        return McpSchema.CallToolResult.builder()
            .addTextContent(JsonUtils.toJsonString(result))
            .structuredContent(result)
            .build();
    }

    private static int parseLimit(Object raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(raw.toString());
            return Math.max(1, Math.min(MAX_LIMIT, value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }
}
