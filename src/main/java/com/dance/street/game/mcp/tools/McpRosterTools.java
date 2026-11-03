package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterMoveBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.bo.TStageRosterRemoveBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITStageRosterService;
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
 * 赛段间名单规则、中间态排位、人工覆盖和确认晋级工具。
 */
@Service
public class McpRosterTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "by_source", "detail", "add_groups", "remove_group", "update_group", "apply",
        "skip", "preview", "candidates", "rebuild", "overrides", "add_override",
        "update_override", "delete_override", "delete_overrides", "reorder", "move",
        "add", "remove", "participants"
    );

    private final ITStageRosterService rosterService;

    public McpRosterTools(ITStageRosterService rosterService) {
        this.rosterService = rosterService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stageId", McpToolSupport.idProperty("Target stage id."));
        properties.put("sourceStageId", McpToolSupport.idProperty("Source stage id."));
        properties.put("groupId", McpToolSupport.idProperty("Roster source-group id."));
        properties.put("overrideId", McpToolSupport.idProperty("Manual override id."));
        properties.put("overrideIds", McpToolSupport.idListProperty("Manual override ids to delete."));
        properties.put("data", McpToolSupport.objectProperty(
            "TStageRosterBo, TStageRosterGroupBo, TStageRosterOverrideBo, TStageRosterOrderBo, "
                + "TStageRosterMoveBo, TStageRosterRemoveBo, or {manualSelections} depending on action."
        ));
        return McpToolSupport.definition(
            "manage_rosters",
            "Manage stage rosters",
            "Manage source rules and the intermediate roster: add/update/remove sources, preview candidates, "
                + "move/add/remove seats, manage overrides, reorder, synchronize pre-advancement, "
                + "apply/confirm rosters, or explicitly skip a stage roster.",
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
            long stageId = requiresStage(action)
                ? McpToolSupport.requiredLong(arguments, "stageId") : 0L;
            Object result = switch (action) {
                case "by_source" -> rosterService.listBySource(
                    McpToolSupport.requiredLong(arguments, "sourceStageId"));
                case "detail" -> {
                    var rosters = rosterService.listByTarget(stageId);
                    if (rosters.isEmpty()) {
                        throw new ServiceException("赛段名单不存在");
                    }
                    yield rosters.getFirst();
                }
                case "add_groups" -> rosterService.addGroups(
                    stageId,
                    McpToolSupport.requiredObject(arguments, "data", TStageRosterBo.class));
                case "remove_group" -> {
                    rosterService.removeGroup(
                        stageId, McpToolSupport.requiredLong(arguments, "groupId"));
                    yield Map.of("removed", true);
                }
                case "update_group" -> {
                    rosterService.updateGroup(
                        stageId,
                        McpToolSupport.requiredLong(arguments, "groupId"),
                        McpToolSupport.requiredObject(arguments, "data", TStageRosterGroupBo.class));
                    yield Map.of("updated", true);
                }
                case "apply" -> rosterService.applyRoster(stageId, manualSelections(arguments));
                case "skip" -> {
                    rosterService.markSkipped(stageId);
                    yield Map.of("skipped", true);
                }
                case "preview" -> rosterService.previewAssembled(stageId);
                case "candidates" -> rosterService.candidates(stageId);
                case "rebuild" -> rosterService.rebuildEntries(stageId);
                case "overrides" -> rosterService.listOverrides(stageId);
                case "add_override" -> rosterService.addOverride(
                    stageId,
                    McpToolSupport.requiredObject(arguments, "data", TStageRosterOverrideBo.class));
                case "update_override" -> {
                    rosterService.updateOverride(
                        stageId,
                        McpToolSupport.requiredLong(arguments, "overrideId"),
                        McpToolSupport.requiredObject(arguments, "data", TStageRosterOverrideBo.class));
                    yield Map.of("updated", true);
                }
                case "delete_override" -> {
                    rosterService.deleteOverride(
                        stageId, McpToolSupport.requiredLong(arguments, "overrideId"));
                    yield Map.of("deleted", true);
                }
                case "delete_overrides" -> {
                    List<Long> ids = McpToolSupport.longList(arguments, "overrideIds");
                    if (ids.isEmpty()) {
                        throw new ServiceException("overrideIds 不能为空");
                    }
                    rosterService.deleteOverrides(stageId, ids);
                    yield Map.of("deleted", ids.size());
                }
                case "reorder" -> {
                    rosterService.reorderRoster(stageId, orderItems(arguments));
                    yield Map.of("reordered", true);
                }
                case "move" -> rosterService.moveRosterRow(
                    stageId,
                    McpToolSupport.requiredObject(arguments, "data", TStageRosterMoveBo.class));
                case "add" -> rosterService.addRosterRow(
                    stageId,
                    McpToolSupport.requiredObject(arguments, "data", TStageRosterOverrideBo.class));
                case "remove" -> rosterService.removeRosterRows(
                    stageId,
                    McpToolSupport.requiredObject(arguments, "data", TStageRosterRemoveBo.class));
                case "participants" -> rosterService.listStageParticipants(stageId);
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result("manage_rosters", action, result, arguments);
        } catch (Exception error) {
            return McpToolSupport.failure("manage_rosters", action,
                request.arguments() == null ? Map.of() : request.arguments(), error);
        }
    }

    private static boolean requiresStage(String action) {
        return !Set.of("by_source").contains(action);
    }

    private static Map<Long, List<Long>> manualSelections(Map<String, Object> arguments) {
        Object raw = arguments.get("data");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map && map.containsKey("manualSelections")) {
            raw = map.get("manualSelections");
        }
        return McpToolSupport.longListMap(raw);
    }

    private static List<TStageRosterOrderBo.Item> orderItems(Map<String, Object> arguments) {
        Object raw = arguments.get("data");
        if (raw == null) {
            throw new ServiceException("data 不能为空");
        }
        if (raw instanceof Map<?, ?> map && map.containsKey("items")) {
            raw = map.get("items");
        }
        if (!(raw instanceof List<?> values)) {
            throw new ServiceException("data.items 必须是数组");
        }
        List<TStageRosterOrderBo.Item> items = new ArrayList<>(values.size());
        for (Object value : values) {
            items.add(McpToolSupport.convert(value, TStageRosterOrderBo.Item.class));
        }
        return items;
    }
}
