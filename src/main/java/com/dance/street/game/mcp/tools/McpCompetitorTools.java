package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.CheckInEditBo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITCompetitorMemberService;
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITMatchParticipantService;
import com.dance.street.game.service.ITPlayerService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参赛单位、成员关系和场次参赛明细工具。
 */
@Service
public class McpCompetitorTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "update", "delete", "stage_competitors",
        "register_player", "register_players", "edit_player_entry", "cancel_player_entry",
        "member_list", "member_get", "member_create", "member_update", "member_delete",
        "participant_list", "participant_get", "participant_create",
        "participant_update", "participant_delete"
    );

    private final ITCompetitorService competitorService;
    private final ITCompetitorMemberService memberService;
    private final ITMatchParticipantService participantService;
    private final ITPlayerService playerService;

    public McpCompetitorTools(ITCompetitorService competitorService,
                              ITCompetitorMemberService memberService,
                              ITMatchParticipantService participantService,
                              ITPlayerService playerService) {
        this.competitorService = competitorService;
        this.memberService = memberService;
        this.participantService = participantService;
        this.playerService = playerService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Competitor/member/participant id."));
        properties.put("playerId", McpToolSupport.idProperty(
            "Player id for register_player/cancel_player_entry. The active Player list may serialize it as a string."));
        properties.put("checkInType", Map.of(
            "type", "string",
            "enum", List.of("CREATE", "JOIN"),
            "description", "CREATE creates a new Competitor and member link. JOIN adds the Player to competitorId."
        ));
        properties.put("competitorNumber", Map.of("type", "string",
            "description", "Required when checkInType=CREATE. Tournament bib number."));
        properties.put("competitorId", McpToolSupport.idProperty(
            "Required when checkInType=JOIN."));
        properties.put("name", Map.of("type", "string",
            "description", "Optional name override for this entry."));
        properties.put("avatar", Map.of("type", "string",
            "description", "Optional avatar override for this entry."));
        properties.put("matchId", McpToolSupport.idProperty(
            "Optional target circle match id for AUDITION/RANK."));
        properties.put("zoneIndex", Map.of("type", "integer", "minimum", 1,
            "description", "One-based target circle when matchId is unavailable."));
        properties.put("entries", Map.of(
            "type", "array",
            "minItems", 1,
            "items", checkInEntrySchema(),
            "description", "Bulk entries for register_players. Each item uses the same fields as register_player."
        ));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("stageId", McpToolSupport.idProperty("Stage id for stage_competitors."));
        properties.put("data", McpToolSupport.objectProperty(
            "TCompetitorBo, TCompetitorMemberBo, or TMatchParticipantBo depending on action; "
                + "also used as list filters."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_competitors",
            "Manage competitors and entries",
            "Manage the Competitor domain: stage-specific entries, competitor-member links, and match participants. "
                + "`register_player` is the explicit bridge from a Player profile to a Competitor entry; "
                + "`register_players` registers multiple entries in one call and returns per-person results. "
                + "Use checkInType=CREATE with competitorNumber, or JOIN with competitorId. "
                + "`cancel_player_entry` removes the entry link without deleting the Player profile. Most match "
                + "participants are generated by stage orchestration; direct CRUD is for controlled repairs.",
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
                case "list" -> competitorService.queryPageList(
                    optional(arguments, TCompetitorBo.class, new TCompetitorBo()),
                    McpToolSupport.pageQuery(arguments));
                case "get" -> competitorService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "create" -> competitorService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TCompetitorBo.class));
                case "update" -> competitorService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TCompetitorBo.class));
                case "delete" -> competitorService.deleteWithValidByIds(requiredIds(arguments), true);
                case "stage_competitors" -> {
                    TCompetitorBo bo = new TCompetitorBo();
                    bo.setStageId(McpToolSupport.requiredLong(arguments, "stageId"));
                    yield competitorService.queryList(bo);
                }
                case "register_player" -> registerPlayer(arguments);
                case "register_players" -> registerPlayers(arguments);
                case "edit_player_entry" -> playerService.editCheckIn(
                    McpToolSupport.requiredObject(arguments, "data", CheckInEditBo.class));
                case "cancel_player_entry" -> {
                    playerService.cancelCheckIn(McpToolSupport.requiredLong(arguments, "playerId"));
                    yield Map.of("cancelled", true);
                }
                case "member_list" -> memberService.queryPageList(
                    optional(arguments, TCompetitorMemberBo.class, new TCompetitorMemberBo()),
                    McpToolSupport.pageQuery(arguments));
                case "member_get" -> memberService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "member_create" -> memberService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TCompetitorMemberBo.class));
                case "member_update" -> memberService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TCompetitorMemberBo.class));
                case "member_delete" -> memberService.deleteWithValidByIds(requiredIds(arguments), true);
                case "participant_list" -> participantService.queryPageList(
                    optional(arguments, TMatchParticipantBo.class, new TMatchParticipantBo()),
                    McpToolSupport.pageQuery(arguments));
                case "participant_get" -> participantService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "participant_create" -> participantService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchParticipantBo.class));
                case "participant_update" -> participantService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchParticipantBo.class));
                case "participant_delete" ->
                    participantService.deleteWithValidByIds(requiredIds(arguments), true);
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            if (result instanceof McpSchema.CallToolResult toolResult) {
                return toolResult;
            }
            return McpToolSupport.result("manage_competitors", action, result, arguments);
        } catch (Exception error) {
            return McpToolSupport.failure("manage_competitors", action,
                request.arguments() == null ? Map.of() : request.arguments(), error);
        }
    }

    private static <T> T optional(Map<String, Object> arguments, Class<T> type, T fallback) {
        T value = McpToolSupport.optionalObject(arguments, "data", type);
        return value == null ? fallback : value;
    }

    private static List<Long> requiredIds(Map<String, Object> arguments) {
        List<Long> ids = McpToolSupport.longList(arguments, "ids");
        if (ids.isEmpty()) {
            throw new ServiceException("ids 不能为空");
        }
        return ids;
    }

    private Object registerPlayer(Map<String, Object> arguments) {
        CheckInBo bo = checkInArguments(arguments, true);
        var registered = playerService.checkIn(bo);
        return McpToolSupport.result("register_player", registered,
            registrationContext(registered));
    }

    private Object registerPlayers(Map<String, Object> arguments) {
        Object raw = arguments.get("entries");
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
            throw new ServiceException("register_players 需要非空 entries 数组");
        }
        List<Map<String, Object>> registered = new java.util.ArrayList<>();
        List<Map<String, Object>> errors = new java.util.ArrayList<>();
        Long activeTournamentId = null;
        Long activeStageId = null;
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                errors.add(Map.of("error", "entries 中的每一项都必须是对象"));
                continue;
            }
            Map<String, Object> entryArguments = new LinkedHashMap<>();
            entry.forEach((key, value) -> entryArguments.put(String.valueOf(key), value));
            Long playerId = McpToolSupport.optionalLong(entryArguments, "playerId");
            try {
                CheckInBo bo = checkInArguments(entryArguments, false);
                var player = playerService.checkIn(bo);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playerId", player.getId());
                item.put("name", player.getName());
                item.put("competitorId", player.getCompetitorId());
                item.put("stageId", player.getCompetitorVo() == null
                    ? null : player.getCompetitorVo().getStageId());
                registered.add(item);
                if (activeTournamentId == null) {
                    activeTournamentId = player.getTournamentId();
                    activeStageId = item.get("stageId") instanceof Long stageId ? stageId : null;
                }
            } catch (Exception error) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playerId", playerId);
                item.put("error", error.getMessage());
                errors.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", entries.size());
        result.put("succeeded", registered.size());
        result.put("failed", errors.size());
        result.put("registered", registered);
        result.put("errors", errors);
        return McpToolSupport.result("register_players", result,
            McpToolSupport.activeContext(activeTournamentId, activeStageId, null));
    }

    private static CheckInBo checkInArguments(Map<String, Object> arguments, boolean allowData) {
        if (allowData && arguments.get("data") instanceof Map<?, ?> data
            && arguments.get("checkInType") == null) {
            return McpToolSupport.convert(data, CheckInBo.class);
        }
        CheckInBo bo = new CheckInBo();
        bo.setPlayerId(McpToolSupport.requiredLong(arguments, "playerId"));
        bo.setCheckInType(McpToolSupport.optionalString(arguments, "checkInType"));
        bo.setCompetitorNumber(McpToolSupport.optionalString(arguments, "competitorNumber"));
        bo.setCompetitorId(McpToolSupport.optionalLong(arguments, "competitorId"));
        bo.setName(McpToolSupport.optionalString(arguments, "name"));
        bo.setAvatar(McpToolSupport.optionalString(arguments, "avatar"));
        bo.setMatchId(McpToolSupport.optionalLong(arguments, "matchId"));
        bo.setZoneIndex(McpToolSupport.optionalInt(arguments, "zoneIndex"));
        if (bo.getCheckInType() == null || bo.getCheckInType().isBlank()) {
            throw new ServiceException(
                "checkInType 不能为空：CREATE 新建参赛单位，JOIN 加入已有参赛单位");
        }
        if ("CREATE".equalsIgnoreCase(bo.getCheckInType())
            && (bo.getCompetitorNumber() == null || bo.getCompetitorNumber().isBlank())) {
            throw new ServiceException("checkInType=CREATE 时必须提供 competitorNumber");
        }
        if ("JOIN".equalsIgnoreCase(bo.getCheckInType()) && bo.getCompetitorId() == null) {
            throw new ServiceException("checkInType=JOIN 时必须提供 competitorId");
        }
        return bo;
    }

    private static Map<String, Object> registrationContext(
        com.dance.street.game.domain.vo.TPlayerVo player) {
        Long stageId = player.getCompetitorVo() == null
            ? null : player.getCompetitorVo().getStageId();
        return McpToolSupport.activeContext(player.getTournamentId(), stageId, null);
    }

    private static Map<String, Object> checkInEntrySchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("playerId", McpToolSupport.idProperty("Player id."));
        properties.put("checkInType", Map.of("type", "string", "enum", List.of("CREATE", "JOIN")));
        properties.put("competitorNumber", Map.of("type", "string",
            "description", "Required when checkInType=CREATE."));
        properties.put("competitorId", McpToolSupport.idProperty(
            "Required when checkInType=JOIN."));
        properties.put("name", Map.of("type", "string"));
        properties.put("avatar", Map.of("type", "string"));
        properties.put("matchId", McpToolSupport.idProperty("Optional circle match id."));
        properties.put("zoneIndex", Map.of("type", "integer", "minimum", 1));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("playerId", "checkInType"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
