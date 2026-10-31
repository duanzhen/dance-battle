package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITTournamentService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 赛事生命周期、模板创建和导播凭证管理。
 */
@Service
public class McpTournamentManagementTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "create_from_template", "update", "delete",
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
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_tournaments",
            "Manage tournaments",
            "Manage the full tournament lifecycle: list/get/create/update/delete, create from a built-in template, "
                + "and read or rotate the director auth key. Reuses the application's transaction and validation services.",
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
                case "create" -> tournamentService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TTournamentBo.class));
                case "create_from_template" -> tournamentService.createByTemplate(
                    McpToolSupport.requiredObject(arguments, "data", TTournamentTemplateBo.class));
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
}
