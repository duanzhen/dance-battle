package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITPlayerService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 选手档案、签到和批量导入工具。
 */
@Service
public class McpPlayerTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "update", "delete", "import"
    );

    private final ITPlayerService playerService;

    public McpPlayerTools(ITPlayerService playerService) {
        this.playerService = playerService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Player id."));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("tournamentId", McpToolSupport.idProperty(
            "Tournament id for list/import. Use the active tournament when the user continues the current context."));
        Map<String, Object> playerImportSchema = new LinkedHashMap<>();
        playerImportSchema.put("type", "object");
        playerImportSchema.put("additionalProperties", false);
        playerImportSchema.put("properties", Map.of(
            "name", Map.of("type", "string", "minLength", 1,
                "description", "Player display name."),
            "idCard", Map.of("type", "string",
                "description", "Optional identity value; omit when not provided."),
            "tags", Map.of(
                "type", List.of("string", "array"),
                "items", Map.of("type", "string"),
                "description", "Optional string or string array."),
            "remark", Map.of("type", "string", "description", "Optional remark.")
        ));
        playerImportSchema.put("required", List.of("name"));
        properties.put("players", Map.of(
            "type", "array",
            "items", playerImportSchema,
            "minItems", 1,
            "description", "Structured player records for action=import. Example: "
                + "[{\"name\":\"林晓\"},{\"name\":\"陈宇\"}]."
        ));
        properties.put("data", Map.of(
            "anyOf", List.of(
                Map.of("type", "object"),
                Map.of("type", "array", "items", playerImportSchema)
            ),
            "description", "Object for create/update/list/check-in. For legacy import calls, "
                + "an array is also accepted; new callers should use players[]."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_players",
            "Manage player profiles",
            "Manage the Player (natural person) domain only. Player records are not Competitor entries. "
                + "For action=import, pass "
                + "tournamentId plus players=[{name,idCard?,tags?,remark?}]. This creates player records only; "
                + "use manage_competitors.register_player to enter a Player as a stage Competitor. "
                + "Import returns the imported count and active context.",
            ACTIONS,
            properties,
            false
        );
    }

    @Override
    public McpSchema.CallToolResult execute(McpTransportContext transportContext,
                                            McpSchema.CallToolRequest request) {
        String action = null;
        try {
            McpToolSupport.authorize(transportContext);
            Map<String, Object> arguments = McpToolSupport.arguments(request);
            action = McpToolSupport.action(request, ACTIONS);
            Object result = switch (action) {
                case "list" -> {
                    TPlayerBo bo = McpToolSupport.optionalObject(arguments, "data", TPlayerBo.class);
                    bo = bo == null ? new TPlayerBo() : bo;
                    Long tournamentId = McpToolSupport.optionalLong(arguments, "tournamentId");
                    if (tournamentId != null) {
                        bo.setTournamentId(tournamentId);
                    }
                    yield playerService.queryPageList(
                        bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "get" -> playerService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "create" -> playerService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TPlayerBo.class));
                case "update" -> playerService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TPlayerBo.class));
                case "delete" -> playerService.deleteWithValidByIds(requiredIds(arguments), true);
                case "import" -> {
                    long tournamentId = McpToolSupport.requiredLong(arguments, "tournamentId");
                    List<PlayerImportVo> players = importRecords(arguments);
                    Map<String, Object> imported = new LinkedHashMap<>();
                    imported.put("imported", playerService.importPlayers(players, tournamentId));
                    imported.put("tournamentId", tournamentId);
                    yield McpToolSupport.result("import", imported,
                        McpToolSupport.activeContext(tournamentId, null, null));
                }
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            if (result instanceof McpSchema.CallToolResult toolResult) {
                return toolResult;
            }
            return McpToolSupport.result(action, result);
        } catch (Exception error) {
            return McpToolSupport.failure(action, error);
        }
    }

    private static List<Long> requiredIds(Map<String, Object> arguments) {
        List<Long> ids = McpToolSupport.longList(arguments, "ids");
        if (ids.isEmpty()) {
            throw new ServiceException("ids 不能为空");
        }
        return ids;
    }

    private static List<PlayerImportVo> importRecords(Map<String, Object> arguments) {
        Object raw = arguments.get("players");
        if (raw == null) {
            raw = arguments.get("data");
        }
        if (!(raw instanceof List<?> values)) {
            throw new ServiceException("import 需要 players 数组，每项至少包含 name");
        }
        List<PlayerImportVo> players = new ArrayList<>(values.size());
        for (Object value : values) {
            players.add(toImportRecord(value));
        }
        return players;
    }

    private static PlayerImportVo toImportRecord(Object value) {
        if (value instanceof PlayerImportVo player) {
            return player;
        }
        if (!(value instanceof Map<?, ?> record)) {
            throw new ServiceException("players 数组中的每一项都必须是对象");
        }
        PlayerImportVo player = new PlayerImportVo();
        player.setName(stringValue(record.get("name")));
        player.setIdCard(stringValue(record.get("idCard")));
        player.setRemark(stringValue(record.get("remark")));
        Object tags = record.get("tags");
        if (tags instanceof List<?> || tags instanceof Object[]) {
            player.setTags(JsonUtils.toJsonString(tags));
        } else {
            player.setTags(stringValue(tags));
        }
        return player;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
