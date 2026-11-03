package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TMatchRoundBo;
import com.dance.street.game.domain.bo.TRoundScoreBo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITMatchRoundService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITRoundScoreService;
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
 * 场次、轮次、原始打分和比赛现场执行工具。
 */
@Service
public class McpMatchTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "update", "delete",
        "submit_result", "publish_result", "start", "cancel_start", "reset",
        "reset_round", "current_competitor", "set_current_competitor",
        "designate_tiebreak_advance",
        "create_free_match", "delete_free_match", "select_free_advancers",
        "round_list", "round_get", "round_create", "round_update", "round_delete",
        "score_list", "score_get", "score_create", "score_update", "score_delete"
    );

    private final ITMatchService matchService;
    private final ITMatchResultService resultService;
    private final ITStageLifecycleService lifecycleService;
    private final ITMatchRoundService roundService;
    private final ITRoundScoreService scoreService;

    public McpMatchTools(ITMatchService matchService,
                         ITMatchResultService resultService,
                         ITStageLifecycleService lifecycleService,
                         ITMatchRoundService roundService,
                         ITRoundScoreService scoreService) {
        this.matchService = matchService;
        this.resultService = resultService;
        this.lifecycleService = lifecycleService;
        this.roundService = roundService;
        this.scoreService = scoreService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", McpToolSupport.idProperty("Match/round/score id."));
        properties.put("ids", McpToolSupport.idsProperty());
        properties.put("matchId", McpToolSupport.idProperty("Match id."));
        properties.put("stageId", McpToolSupport.idProperty("Stage id."));
        properties.put("competitorAId", McpToolSupport.idProperty("First free-match competitor."));
        properties.put("competitorBId", McpToolSupport.idProperty("Second free-match competitor."));
        properties.put("competitorIds", McpToolSupport.idListProperty(
            "Free-match advancing competitors in seed order."));
        properties.put("roundSequence", McpToolSupport.idProperty("One-based round sequence to reset."));
        properties.put("currentCompetitorId", Map.of(
            "description", "Current on-stage competitor. Omit to leave unchanged; pass null to clear.",
            "anyOf", List.of(
                McpToolSupport.idProperty("Current on-stage competitor."),
                Map.of("type", "null")
            )
        ));
        properties.put("data", McpToolSupport.objectProperty(
            "TMatchBo, SubmitResultBo, TMatchRoundBo, or TRoundScoreBo depending on action; "
                + "also used as list filters."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_matches",
            "Manage matches and execution",
            "Manage matches, rounds, and raw score records; submit/publish/start/reset results; run arena/free-match "
                + "operations; and mark the current on-stage competitor. Domain services enforce all state transitions.",
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
                    TMatchBo bo = McpToolSupport.optionalObject(arguments, "data", TMatchBo.class);
                    yield matchService.queryPageList(
                        bo == null ? new TMatchBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "get" -> matchService.queryById(McpToolSupport.requiredLong(arguments, "id"));
                case "create" -> matchService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchBo.class));
                case "update" -> matchService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchBo.class));
                case "delete" -> matchService.deleteWithValidByIds(requiredIds(arguments), true);
                case "submit_result" -> submitResult(arguments);
                case "publish_result" -> resultService.publishResult(
                    McpToolSupport.requiredLong(arguments, "matchId"));
                case "start" -> {
                    resultService.startMatch(McpToolSupport.requiredLong(arguments, "matchId"));
                    yield Map.of("started", true);
                }
                case "cancel_start" -> {
                    resultService.cancelStartMatch(
                        McpToolSupport.requiredLong(arguments, "matchId"));
                    yield Map.of("cancelled", true);
                }
                case "reset" -> {
                    resultService.resetMatch(McpToolSupport.requiredLong(arguments, "matchId"));
                    yield Map.of("reset", true);
                }
                case "reset_round" -> {
                    resultService.resetRound(
                        McpToolSupport.requiredLong(arguments, "matchId"),
                        McpToolSupport.requiredLong(arguments, "roundSequence"));
                    yield Map.of("reset", true);
                }
                case "current_competitor" -> lifecycleService.getMatchCurrentCompetitor(
                    McpToolSupport.requiredLong(arguments, "matchId"));
                case "set_current_competitor" -> {
                    if (!arguments.containsKey("currentCompetitorId")) {
                        yield Map.of("updated", false, "unchanged", true);
                    }
                    lifecycleService.setMatchCurrentCompetitor(
                        McpToolSupport.requiredLong(arguments, "matchId"),
                        McpToolSupport.optionalLong(arguments, "currentCompetitorId")
                    );
                    yield Map.of("updated", true);
                }
                case "designate_tiebreak_advance" -> {
                    resultService.designateAuditionTiebreakAdvance(
                        McpToolSupport.requiredLong(arguments, "matchId"),
                        McpToolSupport.longList(arguments, "competitorIds")
                    );
                    yield Map.of("designated", true);
                }
                case "create_free_match" -> lifecycleService.createFreeMatch(
                    McpToolSupport.requiredLong(arguments, "stageId"),
                    McpToolSupport.requiredLong(arguments, "competitorAId"),
                    McpToolSupport.requiredLong(arguments, "competitorBId")
                );
                case "delete_free_match" -> {
                    lifecycleService.deleteFreeMatch(
                        McpToolSupport.requiredLong(arguments, "matchId"));
                    yield Map.of("deleted", true);
                }
                case "select_free_advancers" -> lifecycleService.selectFreeMatchAdvancers(
                    McpToolSupport.requiredLong(arguments, "stageId"),
                    McpToolSupport.longList(arguments, "competitorIds")
                );
                case "round_list" -> {
                    TMatchRoundBo bo = McpToolSupport.optionalObject(
                        arguments, "data", TMatchRoundBo.class);
                    yield roundService.queryPageList(
                        bo == null ? new TMatchRoundBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "round_get" -> roundService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "round_create" -> roundService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchRoundBo.class));
                case "round_update" -> roundService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TMatchRoundBo.class));
                case "round_delete" ->
                    roundService.deleteWithValidByIds(requiredIds(arguments), true);
                case "score_list" -> {
                    TRoundScoreBo bo = McpToolSupport.optionalObject(
                        arguments, "data", TRoundScoreBo.class);
                    yield scoreService.queryPageList(
                        bo == null ? new TRoundScoreBo() : bo,
                        McpToolSupport.pageQuery(arguments)
                    );
                }
                case "score_get" -> scoreService.queryById(
                    McpToolSupport.requiredLong(arguments, "id"));
                case "score_create" -> scoreService.insertByBo(
                    McpToolSupport.requiredObject(arguments, "data", TRoundScoreBo.class));
                case "score_update" -> scoreService.updateByBo(
                    McpToolSupport.requiredObject(arguments, "data", TRoundScoreBo.class));
                case "score_delete" ->
                    scoreService.deleteWithValidByIds(requiredIds(arguments), true);
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result("manage_matches", action, result, arguments);
        } catch (Exception error) {
            return McpToolSupport.failure("manage_matches", action,
                request.arguments() == null ? Map.of() : request.arguments(), error);
        }
    }

    private Object submitResult(Map<String, Object> arguments) {
        SubmitResultBo bo = McpToolSupport.requiredObject(arguments, "data", SubmitResultBo.class);
        bo.setMatchId(McpToolSupport.requiredLong(arguments, "matchId"));
        return resultService.submitResult(bo);
    }

    private static List<Long> requiredIds(Map<String, Object> arguments) {
        List<Long> ids = McpToolSupport.longList(arguments, "ids");
        if (ids.isEmpty()) {
            throw new ServiceException("ids 不能为空");
        }
        return ids;
    }
}
