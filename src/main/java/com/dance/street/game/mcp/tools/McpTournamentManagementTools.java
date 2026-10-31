package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITTournamentService;
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
 * 赛事生命周期、模板创建和导播凭证管理。
 */
@Service
public class McpTournamentManagementTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create_blank", "create_from_template", "update", "delete",
        "get_auth_key", "regenerate_auth_key"
    );

    private final ITTournamentService tournamentService;

    public McpTournamentManagementTools(ITTournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Tournament id."));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("data", McpToolSupport.objectProperty(
            "TournamentBo fields for create/update, list filters, or tournament template fields."
        ));
        properties.put("confirmed", Map.of(
            "type", "boolean",
            "description", "Set true only after the user explicitly confirmed the tournament name, "
                + "creation mode (blank draft or built-in template), and templateCode when applicable."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_tournaments",
            "Manage tournaments",
            "Manage the full tournament lifecycle with two explicit creation paths: create_blank creates only "
                + "an editable tournament draft, while create_from_template creates the configured stage chain. "
                + "Creating a tournament never guesses missing details; "
                + "instead it returns clarification_required. Set confirmed=true only after explicit user confirmation.",
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
                    TTournamentBo bo = McpToolSupport.optionalObject(arguments, "data", TTournamentBo.class);
                    yield tournamentService.queryPageList(
                        bo == null ? new TTournamentBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "get" -> tournamentService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "create_blank" -> createBlankTournament(arguments, action);
                case "create_from_template" -> createTournamentFromTemplate(arguments);
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
            if (result instanceof McpSchema.CallToolResult toolResult) {
                return toolResult;
            }
            return McpToolSupport.result(action, result);
        } catch (Exception error) {
            return McpToolSupport.failure(action, error);
        }
    }

    private Object createBlankTournament(Map<String, Object> arguments, String action) {
        List<String> missing = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        TTournamentBo bo = McpToolSupport.optionalObject(arguments, "data", TTournamentBo.class);
        if (bo == null || bo.getName() == null || bo.getName().isBlank()) {
            missing.add("data.name");
            questions.add("请确认新赛事的名称。");
        }
        if (!Boolean.TRUE.equals(McpToolSupport.optionalBoolean(arguments, "confirmed"))) {
            missing.add("confirmed");
            questions.add("请确认创建方式：建立空白赛事草稿，还是使用内置模板创建完整赛段结构？");
        }
        if (!missing.isEmpty()) {
            return McpToolSupport.clarification(action, missing, questions);
        }
        var tournament = tournamentService.insertByBo(bo);
        return McpToolSupport.result(action, tournament,
            McpToolSupport.activeContext(tournament.getId(), null, null));
    }

    private Object createTournamentFromTemplate(Map<String, Object> arguments) {
        List<String> missing = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        TTournamentTemplateBo bo = McpToolSupport.optionalObject(
            arguments, "data", TTournamentTemplateBo.class);
        if (bo == null || bo.getName() == null || bo.getName().isBlank()) {
            missing.add("data.name");
            questions.add("请确认新赛事的名称。");
        }
        if (bo == null || bo.getTemplateCode() == null || bo.getTemplateCode().isBlank()) {
            missing.add("data.templateCode");
            questions.add("请选择赛事模式：AUDITION_32、AUDITION_16 或 AUDITION_ARENA。");
        }
        if (!Boolean.TRUE.equals(McpToolSupport.optionalBoolean(arguments, "confirmed"))) {
            missing.add("confirmed");
            questions.add("请确认已核对赛事名称和赛段模式，是否按该配置创建赛事？");
        }
        if (!missing.isEmpty()) {
            return McpToolSupport.clarification("create_from_template", missing, questions);
        }
        var tournament = tournamentService.createByTemplate(bo);
        return McpToolSupport.result("create_from_template", tournament,
            McpToolSupport.activeContext(tournament.getId(), null, null));
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
}
