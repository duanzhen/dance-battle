package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TVisSceneBo;
import com.dance.street.game.domain.bo.TVisScreenBo;
import com.dance.street.game.domain.bo.TVisWidgetBo;
import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.vo.TVisScreenVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITVisSceneService;
import com.dance.street.game.service.ITVisScreenService;
import com.dance.street.game.service.ITVisWidgetService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可视化场景、屏幕、控件和投射控制工具。
 */
@Service
public class McpDisplayTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "scene_list", "scene_get", "scene_create", "scene_update", "scene_delete",
        "widget_list", "widget_get", "widget_create", "widget_update", "widget_delete",
        "widget_move_layer", "widget_reorder", "widget_timer_state", "widget_video_state",
        "screen_list", "screen_create", "screen_update", "screen_delete",
        "project_scene", "clear_projection"
    );

    private final ITVisSceneService sceneService;
    private final ITVisWidgetService widgetService;
    private final ITVisScreenService screenService;
    private final TournamentSseEmitterManager screenEventManager;

    public McpDisplayTools(ITVisSceneService sceneService,
                           ITVisWidgetService widgetService,
                           ITVisScreenService screenService,
                           TournamentSseEmitterManager screenEventManager) {
        this.sceneService = sceneService;
        this.widgetService = widgetService;
        this.screenService = screenService;
        this.screenEventManager = screenEventManager;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Scene/widget id."));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("tournamentId", McpToolSupport.idProperty("Tournament id."));
        properties.put("sceneId", McpToolSupport.idProperty("Scene id."));
        properties.put("screenId", McpToolSupport.idProperty("Screen id."));
        properties.put("direction", McpToolSupport.stringProperty("Layer move direction: up or down."));
        properties.put("widgetIds", McpToolSupport.idListProperty(
            "Widget ids in top-to-bottom layer order."));
        properties.put("data", McpToolSupport.objectProperty(
            "TVisSceneBo, TVisWidgetBo, or TVisScreenBo depending on action; "
                + "for state actions this is the allowed state patch."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_displays",
            "Manage screen scenes and projection",
            "Manage visual scenes, widgets, screen definitions, layer order, timer/video state, and projection. "
                + "Projection persists current_scene_id and broadcasts sceneSwitch to the selected screen.",
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
                case "scene_list" -> {
                    TVisSceneBo bo = McpToolSupport.optionalObject(arguments, "data", TVisSceneBo.class);
                    yield sceneService.queryPageList(
                        bo == null ? new TVisSceneBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "scene_get" -> sceneService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "scene_create" -> sceneService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisSceneBo.class));
                case "scene_update" -> sceneService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisSceneBo.class));
                case "scene_delete" ->
                    sceneService.deleteWithValidByIds(requiredIds(arguments), true);
                case "widget_list" -> {
                    TVisWidgetBo bo = McpToolSupport.optionalObject(arguments, "data", TVisWidgetBo.class);
                    yield widgetService.queryPageList(
                        bo == null ? new TVisWidgetBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "widget_get" -> widgetService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "widget_create" -> widgetService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisWidgetBo.class));
                case "widget_update" -> widgetService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisWidgetBo.class));
                case "widget_delete" ->
                    widgetService.deleteWithValidByIds(requiredIds(arguments), true);
                case "widget_move_layer" -> {
                    widgetService.moveLayer(
                        McpToolSupport.requiredLong(arguments, "id"),
                        McpToolSupport.optionalString(arguments, "direction"));
                    yield Map.of("moved", true);
                }
                case "widget_reorder" -> {
                    widgetService.reorder(
                        McpToolSupport.requiredLong(arguments, "sceneId"),
                        McpToolSupport.longList(arguments, "widgetIds"));
                    yield Map.of("reordered", true);
                }
                case "widget_timer_state" -> {
                    widgetService.updateTimerState(
                        McpToolSupport.requiredLong(arguments, "id"),
                        statePatch(arguments));
                    yield Map.of("updated", true);
                }
                case "widget_video_state" -> {
                    widgetService.updateVideoState(
                        McpToolSupport.requiredLong(arguments, "id"),
                        statePatch(arguments));
                    yield Map.of("updated", true);
                }
                case "screen_list" -> screenService.listByTournament(
                    McpToolSupport.requiredLong(arguments, "tournamentId"));
                case "screen_create" -> screenService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisScreenBo.class));
                case "screen_update" -> screenService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TVisScreenBo.class));
                case "screen_delete" -> screenService.deleteByIds(requiredIds(arguments));
                case "project_scene" -> project(arguments);
                case "clear_projection" -> clearProjection(arguments);
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result("manage_displays", action, result, arguments);
        } catch (Exception error) {
            return McpToolSupport.failure("manage_displays", action,
                request.arguments() == null ? Map.of() : request.arguments(), error);
        }
    }

    private Object project(Map<String, Object> arguments) {
        long tournamentId = McpToolSupport.requiredLong(arguments, "tournamentId");
        long screenId = McpToolSupport.requiredLong(arguments, "screenId");
        long sceneId = McpToolSupport.requiredLong(arguments, "sceneId");
        assertScreenBelongsTo(tournamentId, screenId);
        TVisSceneVo scene = sceneService.queryById(sceneId);
        if (scene == null || !java.util.Objects.equals(scene.getTournamentId(), tournamentId)) {
            throw new ServiceException("场景不存在或不属于当前赛事");
        }
        bind(tournamentId, screenId, sceneId);
        return Map.of("screenId", screenId, "sceneId", sceneId);
    }

    private Object clearProjection(Map<String, Object> arguments) {
        long tournamentId = McpToolSupport.requiredLong(arguments, "tournamentId");
        long screenId = McpToolSupport.requiredLong(arguments, "screenId");
        assertScreenBelongsTo(tournamentId, screenId);
        bind(tournamentId, screenId, null);
        return Map.of("screenId", screenId, "sceneId", "");
    }

    private void bind(long tournamentId, long screenId, Long sceneId) {
        String rawScreenId = String.valueOf(screenId);
        screenEventManager.setCurrentScene(rawScreenId, sceneId == null ? null : String.valueOf(sceneId));
        screenService.bindCurrentScene(screenId, sceneId);
        String sceneJson = sceneId == null ? "null" : "\"" + sceneId + "\"";
        TournamentSseMessageDto message = new TournamentSseMessageDto();
        message.setMessage(String.format(
            "{\"screenId\":\"%s\",\"sceneId\":%s,\"type\":\"sceneSwitch\"}",
            rawScreenId, sceneJson));
        message.setScreenIds(Collections.singletonList(rawScreenId));
        screenEventManager.publishMessage(message);
    }

    private void assertScreenBelongsTo(long tournamentId, long screenId) {
        List<TVisScreenVo> screens = screenService.listByTournament(tournamentId);
        if (screens.stream().noneMatch(screen -> java.util.Objects.equals(screen.getId(), screenId))) {
            throw new ServiceException("屏幕不存在或不属于当前赛事");
        }
    }

    private static Map<String, Object> statePatch(Map<String, Object> arguments) {
        Object raw = arguments.get("data");
        if (!(raw instanceof Map<?, ?> source)) {
            throw new ServiceException("data 必须是状态补丁对象");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        source.forEach((key, value) -> patch.put(String.valueOf(key), value));
        return patch;
    }

    private static List<Long> requiredIds(Map<String, Object> arguments) {
        List<Long> ids = McpToolSupport.longList(arguments, "ids");
        if (ids.isEmpty()) {
            throw new ServiceException("ids 不能为空");
        }
        return ids;
    }
}
