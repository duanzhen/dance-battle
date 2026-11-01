package com.dance.street.game.mcp.tools;

import com.dance.street.game.mcp.oauth.McpJwtService.McpTokenClaims;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 业务工具共用的参数解析、鉴权和结构化响应。
 */
final class McpToolSupport {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> CONTEXT_ID_KEYS = Set.of(
        "tournamentId", "stageId", "matchId", "playerId", "competitorId",
        "refereeId", "roundId", "sceneId", "screenId", "widgetId",
        "memberId", "participantId", "scoreId", "overrideId"
    );

    private McpToolSupport() {
    }

    static McpSchema.Tool definition(String name, String title, String description,
                                     Collection<String> actions,
                                     Map<String, Object> extraProperties,
                                     boolean readOnly) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
            "type", "string",
            "enum", List.copyOf(actions),
            "description", "Business operation to execute."
        ));
        properties.putAll(extraProperties);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        schema.put("additionalProperties", false);
        return McpSchema.Tool.builder(name, schema)
            .title(title)
            .description(description)
            .annotations(McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(!readOnly)
                .idempotentHint(false)
                .openWorldHint(false)
                .build())
            .build();
    }

    static Map<String, Object> idProperty() {
        return Map.of("type", "integer", "minimum", 1,
            "description", "Primary resource id.");
    }

    static Map<String, Object> idsProperty() {
        return Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 1),
            "minItems", 1, "description", "Primary resource ids.");
    }

    static Map<String, Object> idProperty(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }

    static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> objectProperty(String description) {
        return Map.of("type", "object", "description", description);
    }

    static Map<String, Object> listProperty(String description) {
        return Map.of("type", "array", "items", Map.of("type", "object"),
            "description", description);
    }

    static Map<String, Object> idListProperty(String description) {
        return Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 1),
            "description", description);
    }

    static Map<String, Object> pagingProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pageNum", Map.of("type", "integer", "minimum", 1, "default", 1,
            "description", "One-based page number."));
        properties.put("pageSize", Map.of("type", "integer", "minimum", 1,
            "maximum", MAX_PAGE_SIZE, "default", DEFAULT_PAGE_SIZE,
            "description", "Maximum records returned."));
        return properties;
    }

    static String authorize(McpTransportContext transportContext) {
        if (transportContext == null) {
            throw new ServiceException("MCP 请求上下文缺失");
        }
        Object rawClaims = transportContext.get("claims");
        if (!(rawClaims instanceof McpTokenClaims claims)
            || !"mcp-agent".equals(claims.subject())
            || claims.scope() == null
            || !List.of(claims.scope().split("\\s+")).contains("mcp")) {
            throw new ServiceException("MCP 访问凭证无权执行该业务操作");
        }
        return claims.tokenId();
    }

    static String action(McpSchema.CallToolRequest request, Collection<String> allowed) {
        Map<String, Object> arguments = arguments(request);
        Object raw = arguments.get("action");
        String action = raw == null ? null : raw.toString().trim();
        if (action == null || action.isEmpty()) {
            throw new ServiceException("action 不能为空");
        }
        if (!allowed.contains(action)) {
            throw new ServiceException("不支持的 action: {}", action);
        }
        return action;
    }

    static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    static long requiredLong(Map<String, Object> arguments, String name) {
        Long value = optionalLong(arguments, name);
        if (value == null) {
            throw new ServiceException("{} 不能为空", name);
        }
        return value;
    }

    static Long optionalLong(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("{} 必须是整数", name);
        }
    }

    static Integer optionalInt(Map<String, Object> arguments, String name) {
        Long value = optionalLong(arguments, name);
        return value == null ? null : Math.toIntExact(value);
    }

    static String optionalString(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        return raw == null ? null : raw.toString();
    }

    static Boolean optionalBoolean(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(raw.toString());
    }

    static List<Long> longList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Collection<?> values)) {
            throw new ServiceException("{} 必须是数组", name);
        }
        List<Long> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            try {
                result.add(value instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(value.toString()));
            } catch (NumberFormatException e) {
                throw new ServiceException("{} 只能包含整数", name);
            }
        }
        return result;
    }

    static Map<Long, List<Long>> longListMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> source)) {
            throw new ServiceException("manualSelections 必须是对象");
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Long key;
            try {
                key = Long.valueOf(entry.getKey().toString());
            } catch (NumberFormatException e) {
                throw new ServiceException("manualSelections 的键必须是整数");
            }
            if (!(entry.getValue() instanceof Collection<?> values)) {
                throw new ServiceException("manualSelections 的值必须是数组");
            }
            List<Long> ids = new ArrayList<>();
            for (Object value : values) {
                try {
                    ids.add(value instanceof Number number
                        ? number.longValue()
                        : Long.valueOf(value.toString()));
                } catch (NumberFormatException e) {
                    throw new ServiceException("manualSelections 只能包含整数");
                }
            }
            result.put(key, ids);
        }
        return result;
    }

    static <T> T requiredObject(Map<String, Object> arguments, String name, Class<T> type) {
        T value = optionalObject(arguments, name, type);
        if (value == null) {
            throw new ServiceException("{} 不能为空", name);
        }
        return value;
    }

    static <T> T optionalObject(Map<String, Object> arguments, String name, Class<T> type) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return null;
        }
        return convert(raw, type);
    }

    static <T> T convert(Object raw, Class<T> type) {
        if (raw == null || type.isInstance(raw)) {
            return type.isInstance(raw) ? type.cast(raw) : null;
        }
        try {
            return JsonUtils.parseObject(JsonUtils.toJsonString(raw), type);
        } catch (RuntimeException e) {
            throw new ServiceException("参数格式错误，无法转换为 {}", type.getSimpleName());
        }
    }

    static PageQuery pageQuery(Map<String, Object> arguments) {
        int pageNum = optionalInt(arguments, "pageNum") == null
            ? 1 : Math.max(1, optionalInt(arguments, "pageNum"));
        int pageSize = optionalInt(arguments, "pageSize") == null
            ? DEFAULT_PAGE_SIZE
            : Math.max(1, Math.min(MAX_PAGE_SIZE, optionalInt(arguments, "pageSize")));
        return new PageQuery(pageSize, pageNum);
    }

    static McpSchema.CallToolResult result(String toolName, String action,
                                          Object value, Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("result", value);
        result.put("context", context(toolName, action, arguments, value));
        return McpSchema.CallToolResult.builder()
            .addTextContent(JsonUtils.toJsonString(result))
            .structuredContent(result)
            .isError(false)
            .build();
    }

    static McpSchema.CallToolResult failure(String toolName, String action,
                                            Map<String, Object> arguments, Throwable error) {
        String message = error instanceof ServiceException
            ? error.getMessage()
            : "MCP 工具执行失败: " + error.getMessage();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("error", message);
        result.put("context", context(toolName, action, arguments, null));
        return McpSchema.CallToolResult.builder()
            .addTextContent(JsonUtils.toJsonString(result))
            .structuredContent(result)
            .isError(true)
            .build();
    }

    static Map<String, Object> context(String toolName, String action,
                                       Map<String, Object> arguments, Object value) {
        Map<String, Object> ids = new LinkedHashMap<>();
        collectContextIds(arguments, ids);
        Map<String, Object> resultMap = asMap(value);
        collectContextIds(resultMap, ids);

        String resourceType = resourceType(toolName, action);
        Object primaryId = arguments == null ? null : arguments.get("id");
        if (primaryId == null && resultMap.get("id") != null) {
            primaryId = resultMap.get("id");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tool", toolName);
        context.put("action", action);
        context.put("resourceType", resourceType);
        if (primaryId != null) {
            context.put("resourceId", primaryId);
            String primaryKey = switch (resourceType) {
                case "tournament" -> "tournamentId";
                case "stage" -> "stageId";
                case "match" -> "matchId";
                case "player" -> "playerId";
                case "competitor" -> "competitorId";
                case "referee" -> "refereeId";
                case "round" -> "roundId";
                case "scene" -> "sceneId";
                case "screen" -> "screenId";
                case "widget" -> "widgetId";
                case "competitor_member" -> "memberId";
                case "match_participant" -> "participantId";
                case "round_score" -> "scoreId";
                default -> null;
            };
            if (primaryKey != null) {
                context.putIfAbsent(primaryKey, primaryId);
            }
        }
        ids.forEach(context::putIfAbsent);
        return context;
    }

    private static void collectContextIds(Object source, Map<String, Object> target) {
        if (!(source instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (CONTEXT_ID_KEYS.contains(key) && value != null
                && !(value instanceof Collection<?>)) {
                target.put(key, value);
            } else if (value instanceof Map<?, ?>) {
                collectContextIds(value, target);
            }
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseObject(JsonUtils.toJsonString(value), Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static String resourceType(String toolName, String action) {
        return switch (toolName) {
            case "list_tournaments", "manage_tournaments" -> "tournament";
            case "manage_stages" -> "stage";
            case "manage_rosters" -> "roster";
            case "manage_players" -> "player";
            case "manage_competitors" -> action.startsWith("member_")
                ? "competitor_member"
                : action.startsWith("participant_") ? "match_participant" : "competitor";
            case "manage_referees" -> "referee";
            case "manage_matches" -> action.startsWith("round_")
                ? "round" : action.startsWith("score_") ? "round_score" : "match";
            case "manage_displays" -> action.startsWith("screen_")
                ? "screen" : action.startsWith("scene_") ? "scene" : "widget";
            default -> "unknown";
        };
    }

    static Set<String> actions(String... actions) {
        return new LinkedHashSet<>(List.of(actions));
    }
}
