package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.CheckInEditBo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITPlayerService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
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
        "list", "get", "create", "update", "delete", "checkin", "edit_checkin",
        "cancel_checkin", "import"
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
        properties.put("tournamentId", McpToolSupport.idProperty("Tournament id for import."));
        properties.put("data", McpToolSupport.objectProperty(
            "TPlayerBo for create/update/list, CheckInBo, CheckInEditBo, "
                + "or an array of PlayerImportVo records when action=import."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_players",
            "Manage players and check-in",
            "Manage player profiles and the on-site check-in lifecycle. Import accepts structured player records, "
                + "so the MCP caller can populate a tournament without uploading an Excel file.",
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
                    yield playerService.queryPageList(
                        bo == null ? new TPlayerBo() : bo,
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
                case "checkin" -> playerService.checkIn(
                    McpToolSupport.requiredObject(arguments, "data", CheckInBo.class));
                case "edit_checkin" -> playerService.editCheckIn(
                    McpToolSupport.requiredObject(arguments, "data", CheckInEditBo.class));
                case "cancel_checkin" -> {
                    playerService.cancelCheckIn(McpToolSupport.requiredLong(arguments, "id"));
                    yield Map.of("cancelled", true);
                }
                case "import" -> {
                    List<PlayerImportVo> players = importRecords(arguments);
                    yield Map.of("imported", playerService.importPlayers(
                        players,
                        McpToolSupport.requiredLong(arguments, "tournamentId")
                    ));
                }
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
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
        Object raw = arguments.get("data");
        if (!(raw instanceof List<?> values)) {
            throw new ServiceException("data 必须是选手数组");
        }
        List<PlayerImportVo> players = new ArrayList<>(values.size());
        for (Object value : values) {
            players.add(McpToolSupport.convert(value, PlayerImportVo.class));
        }
        return players;
    }
}
