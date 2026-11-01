package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITTournamentService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 赛事生命周期、模板创建和导播凭证管理。
 */
@Service
public class McpTournamentManagementTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create_from_template", "update", "delete",
        "get_auth_key", "regenerate_auth_key"
    );
    private static final Set<String> TEMPLATE_CODES = Set.of(
        "BLANK", "AUDITION_32", "AUDITION_16", "AUDITION_ARENA"
    );
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile(
        "^(新赛事|新建赛事|未命名赛事|untitled|new tournament)(\\s*[-_ ]?\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})?$",
        Pattern.CASE_INSENSITIVE
    );

    private final ITTournamentService tournamentService;

    public McpTournamentManagementTools(ITTournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
            "type", "string",
            "enum", List.copyOf(ACTIONS),
            "description", "Business operation to execute."
        ));
        properties.put("id", McpToolSupport.idProperty("Tournament id."));
        properties.put("ids", McpToolSupport.idsProperty());
        Map<String, Object> dataProperties = new LinkedHashMap<>();
        dataProperties.put("id", McpToolSupport.idProperty("Tournament id for update."));
        dataProperties.put("name", Map.of(
            "type", "string",
            "minLength", 1,
            "maxLength", 120,
            "description", "Explicit tournament name. Never invent or use a placeholder."
        ));
        dataProperties.put("templateCode", Map.of(
            "type", "string",
            "enum", List.copyOf(TEMPLATE_CODES),
            "description", "Required for create_from_template."
        ));
        dataProperties.put("coverImage", Map.of("type", "string"));
        dataProperties.put("description", Map.of("type", "string"));
        dataProperties.put("status", Map.of("type", "integer", "minimum", 0, "maximum", 2));
        dataProperties.put("logicalWidth", Map.of(
            "type", "integer", "minimum", 1,
            "description", "Optional canvas width. Defaults to 1920 when omitted."
        ));
        dataProperties.put("logicalHeight", Map.of(
            "type", "integer", "minimum", 1,
            "description", "Optional canvas height. Defaults to 1080 when omitted."
        ));
        dataProperties.put("themeConfig", Map.of("type", "string"));
        dataProperties.put("remark", Map.of("type", "string"));
        dataProperties.put("refereeNames", Map.of(
            "type", "array",
            "items", Map.of("type", "string", "minLength", 1),
            "description", "Referee names; array length is the referee count. "
                + "Require at least one unless noRefereeConfirmed=true."
        ));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "object");
        data.put("properties", dataProperties);
        data.put("additionalProperties", false);
        data.put("description", "TournamentBo or TTournamentTemplateBo fields, depending on action.");
        properties.put("data", data);
        properties.put("noRefereeConfirmed", Map.of(
            "type", "boolean",
            "description", "Set true only after the user explicitly confirms creating without referees."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        schema.put("additionalProperties", false);
        schema.put("allOf", List.of(
            actionRequires("get", List.of("id")),
            actionRequires("get_auth_key", List.of("id")),
            actionRequires("regenerate_auth_key", List.of("id")),
            actionRequires("delete", List.of("ids")),
            updateConstraint(),
            templateCreateConstraint()
        ));
        return McpSchema.Tool.builder("manage_tournaments", schema)
            .title("Manage tournaments")
            .description("Create every tournament through create_from_template. "
                + "Use templateCode=BLANK only when the user explicitly confirms a blank tournament; "
                + "otherwise use AUDITION_32, AUDITION_16 or AUDITION_ARENA.")
            .annotations(McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(false)
                .build())
            .build();
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
                    TTournamentBo bo = McpToolSupport.optionalObject(arguments, "data", TTournamentBo.class);
                    yield tournamentService.queryPageList(
                        bo == null ? new TTournamentBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "get" -> tournamentService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "create_from_template" -> {
                    TTournamentTemplateBo bo = McpToolSupport.requiredObject(
                        arguments, "data", TTournamentTemplateBo.class);
                    validateTournamentCreate(bo.getName(), bo.getRefereeNames(), arguments);
                    if (StringUtils.isBlank(bo.getTemplateCode())
                        || !TEMPLATE_CODES.contains(bo.getTemplateCode())) {
                        throw new ServiceException(
                            "templateCode 必须是 BLANK、AUDITION_32、AUDITION_16 或 AUDITION_ARENA");
                    }
                    yield tournamentService.createByTemplate(bo);
                }
                case "update" -> tournamentService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TTournamentBo.class));
                case "delete" -> tournamentService.deleteWithValidByIds(
                    requiredIds(arguments), true);
                case "get_auth_key" -> authKeyResult(
                    tournamentService.getAuthKeyById(McpToolSupport.requiredLong(arguments, "id")));
                case "regenerate_auth_key" -> authKeyResult(
                    tournamentService.regenerateAuthKey(McpToolSupport.requiredLong(arguments, "id")));
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result(action, result);
        } catch (Exception error) {
            return McpToolSupport.failure(action, error);
        }
    }

    private static java.util.List<Long> requiredIds(Map<String, Object> arguments) {
        java.util.List<Long> ids = McpToolSupport.longList(arguments, "ids");
        if (ids.isEmpty()) {
            throw new ServiceException("ids 不能为空");
        }
        return ids;
    }

    private static Map<String, String> authKeyResult(String authKey) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("authKey", authKey);
        return result;
    }

    private static void validateTournamentCreate(String name, List<String> refereeNames,
                                                 Map<String, Object> arguments) {
        if (StringUtils.isBlank(name)) {
            throw new ServiceException("创建赛事前必须取得用户明确提供的赛事名称");
        }
        if (PLACEHOLDER_NAME.matcher(name.trim()).matches()) {
            throw new ServiceException("不能使用默认或占位赛事名称[{}]，请先向用户确认正式名称", name);
        }
        boolean hasReferees = refereeNames != null && !refereeNames.isEmpty();
        if (hasReferees) {
            List<String> names = refereeNames.stream()
                .map(StringUtils::trim)
                .toList();
            if (names.stream().anyMatch(StringUtils::isBlank)) {
                throw new ServiceException("裁判姓名不能为空");
            }
            if (names.stream().distinct().count() != names.size()) {
                throw new ServiceException("裁判姓名不能重复");
            }
        }
        boolean noReferees = Boolean.TRUE.equals(arguments.get("noRefereeConfirmed"));
        if (!hasReferees && !noReferees) {
            throw new ServiceException(
                "创建赛事前必须配置至少一名裁判；若用户明确选择暂无裁判，请传 noRefereeConfirmed=true");
        }
    }

    private static Map<String, Object> actionRequires(String action, List<String> required) {
        return Map.of(
            "if", Map.of(
                "properties", Map.of("action", Map.of("const", action)),
                "required", List.of("action")
            ),
            "then", Map.of("required", required)
        );
    }

    private static Map<String, Object> updateConstraint() {
        return Map.of(
            "if", Map.of(
                "properties", Map.of("action", Map.of("const", "update")),
                "required", List.of("action")
            ),
            "then", Map.of(
                "required", List.of("data"),
                "properties", Map.of(
                    "data", Map.of("required", List.of("id"))
                )
            )
        );
    }

    private static Map<String, Object> templateCreateConstraint() {
        return Map.of(
            "if", Map.of(
                "properties", Map.of("action", Map.of("const", "create_from_template")),
                "required", List.of("action")
            ),
            "then", Map.of(
                "required", List.of("data"),
                "properties", Map.of(
                    "data", Map.of(
                        "required", List.of("name", "templateCode")
                    )
                ),
                "anyOf", refereeDecisionBranches()
            )
        );
    }

    private static List<Map<String, Object>> refereeDecisionBranches() {
        return List.of(
            Map.of(
                "required", List.of("data"),
                "properties", Map.of(
                    "data", Map.of(
                        "required", List.of("refereeNames"),
                        "properties", Map.of(
                            "refereeNames", Map.of("type", "array", "minItems", 1)
                        )
                    )
                )
            ),
            Map.of(
                "required", List.of("noRefereeConfirmed"),
                "properties", Map.of("noRefereeConfirmed", Map.of("const", true))
            )
        );
    }
}
