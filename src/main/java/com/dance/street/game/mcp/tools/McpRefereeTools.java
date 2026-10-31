package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.MatchRefereeBo;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TRefereeBo;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.IRefereeMatchService;
import com.dance.street.game.service.ITMatchRefereeService;
import com.dance.street.game.service.ITRefereeService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 裁判档案、凭证、赛段/场次分配和裁判判罚工具。
 */
@Service
public class McpRefereeTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "update", "delete", "get_auth_key",
        "regenerate_auth_key", "stage_referee_ids", "assign_stage_referees",
        "match_referees", "assign_match_referees", "my_match", "match_scores",
        "submit_score"
    );

    private final ITRefereeService refereeService;
    private final ITRefereeStageService refereeStageService;
    private final ITMatchRefereeService matchRefereeService;
    private final IRefereeMatchService refereeMatchService;
    private final ITStageLifecycleService lifecycleService;

    public McpRefereeTools(ITRefereeService refereeService,
                           ITRefereeStageService refereeStageService,
                           ITMatchRefereeService matchRefereeService,
                           IRefereeMatchService refereeMatchService,
                           ITStageLifecycleService lifecycleService) {
        this.refereeService = refereeService;
        this.refereeStageService = refereeStageService;
        this.matchRefereeService = matchRefereeService;
        this.refereeMatchService = refereeMatchService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Referee id."));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("refereeId", McpToolSupport.idProperty("Acting referee id."));
        properties.put("stageId", McpToolSupport.idProperty("Stage id."));
        properties.put("matchId", McpToolSupport.idProperty("Match id."));
        properties.put("tournamentId", McpToolSupport.idProperty("Tournament id."));
        properties.put("data", McpToolSupport.objectProperty(
            "TRefereeBo, StageRefereeBo, MatchRefereeBo, or SubmitResultBo depending on action."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_referees",
            "Manage referees and scoring",
            "Manage referee profiles and auth keys, assign referees to stages/matches, load a referee's current "
                + "work view, inspect accumulated scores, and submit referee outcomes or score entries through the same "
                + "permission and completeness checks as the referee UI.",
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
                    TRefereeBo bo = McpToolSupport.optionalObject(arguments, "data", TRefereeBo.class);
                    yield refereeService.queryPageList(
                        bo == null ? new TRefereeBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "get" -> refereeService.queryById(McpToolSupport.requiredLong(arguments, "id"));
                case "create" -> refereeService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TRefereeBo.class));
                case "update" -> refereeService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TRefereeBo.class));
                case "delete" -> refereeService.deleteWithValidByIds(requiredIds(arguments), true);
                case "get_auth_key" -> authKeyResult(
                    refereeService.getAuthKeyById(McpToolSupport.requiredLong(arguments, "id")));
                case "regenerate_auth_key" -> authKeyResult(
                    refereeService.regenerateAuthKey(McpToolSupport.requiredLong(arguments, "id")));
                case "stage_referee_ids" -> refereeStageService.getRefereeIdsByStageId(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "assign_stage_referees" -> {
                    StageRefereeBo bo = McpToolSupport.requiredObject(
                        arguments, "data", StageRefereeBo.class);
                    lifecycleService.assertRefereesEditable(bo.getStageId());
                    refereeStageService.assignReferees(bo);
                    yield Map.of("assigned", true);
                }
                case "match_referees" -> matchRefereeService.listByStageId(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "assign_match_referees" -> {
                    matchRefereeService.assign(
                        McpToolSupport.requiredObject(arguments, "data", MatchRefereeBo.class));
                    yield Map.of("assigned", true);
                }
                case "my_match" -> myMatch(arguments);
                case "match_scores" -> refereeMatchService.matchScores(
                    McpToolSupport.requiredLong(arguments, "matchId"),
                    McpToolSupport.requiredLong(arguments, "tournamentId"),
                    McpToolSupport.requiredLong(arguments, "refereeId")
                );
                case "submit_score" -> submitScore(arguments);
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result(action, result);
        } catch (Exception error) {
            return McpToolSupport.failure(action, error);
        }
    }

    private Object myMatch(Map<String, Object> arguments) {
        long refereeId = McpToolSupport.requiredLong(arguments, "refereeId");
        TRefereeVo referee = refereeService.queryById(refereeId);
        if (referee == null) {
            throw new ServiceException("裁判不存在");
        }
        return refereeMatchService.myMatch(
            referee.getTournamentId(),
            refereeId,
            referee.getName(),
            McpToolSupport.optionalLong(arguments, "stageId"),
            McpToolSupport.optionalLong(arguments, "matchId")
        );
    }

    private Object submitScore(Map<String, Object> arguments) {
        long matchId = McpToolSupport.requiredLong(arguments, "matchId");
        long refereeId = McpToolSupport.requiredLong(arguments, "refereeId");
        TRefereeVo referee = refereeService.queryById(refereeId);
        if (referee == null) {
            throw new ServiceException("裁判不存在");
        }
        SubmitResultBo bo = McpToolSupport.requiredObject(arguments, "data", SubmitResultBo.class);
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        return refereeMatchService.submitScore(
            matchId,
            referee.getTournamentId(),
            refereeId,
            referee.getName(),
            bo
        );
    }

    private static List<Long> requiredIds(Map<String, Object> arguments) {
        List<Long> ids = McpToolSupport.longList(arguments, "ids");
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
