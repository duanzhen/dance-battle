package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.flow.CircleLabelService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛段配置、赛段链、生命周期和赛制执行工具。
 */
@Service
public class McpStageTools implements IMcpTool {

    private static final Set<String> ACTIONS = McpToolSupport.actions(
        "list", "get", "create", "update_config", "delete", "first", "flow", "prebracket",
        "circle_labels", "initialize", "generate_matches", "ensure_circles",
        "append_competitor", "withdraw_competitor", "temp_withdraw_competitor",
        "start", "complete", "reset_to_draft",
        "arena_next", "arena_overview", "audition_result", "rank_detail", "seed_order",
        "adjust_advancement", "confirm_roster"
    );

    private final ITStageService stageService;
    private final ITStageLifecycleService lifecycleService;
    private final CircleLabelService circleLabelService;

    public McpStageTools(ITStageService stageService,
                         ITStageLifecycleService lifecycleService,
                         CircleLabelService circleLabelService) {
        this.stageService = stageService;
        this.lifecycleService = lifecycleService;
        this.circleLabelService = circleLabelService;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stageId", McpToolSupport.idProperty("Stage id."));
        properties.put("tournamentId", McpToolSupport.idProperty("Tournament id."));
        properties.put("competitorId", McpToolSupport.idProperty("Competitor id."));
        properties.put("matchId", McpToolSupport.idProperty("Target circle/match id."));
        properties.put("zoneIndex", McpToolSupport.idProperty("One-based circle index when matchId is unknown."));
        properties.put("competitorIds", McpToolSupport.idListProperty(
            "Competitors for seed order or advancement adjustment."));
        properties.put("data", McpToolSupport.objectProperty(
            "TStageBo for create/list, TStageConfigBo for update_config, InitializeStageBo, "
                + "GenerateMatchesBo, or SeedOrderBo depending on action."
        ));
        properties.putAll(McpToolSupport.pagingProperties());
        return McpToolSupport.definition(
            "manage_stages",
            "Manage stage workflow",
            "Configure stages and run the complete stage state machine: initialize rosters, generate matches, "
                + "start/complete/reset, inspect flow/pre-brackets/results, manage arena rotation, set seed order, "
                + "confirm advancement, and assign competitors to circles.",
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
                case "list" -> list(arguments);
                case "get" -> stageService.queryById(McpToolSupport.requiredLong(arguments, "stageId"));
                case "create" -> create(arguments);
                case "update_config" -> updateConfig(arguments);
                case "delete" -> stageService.deleteWithValidByIds(
                    List.of(McpToolSupport.requiredLong(arguments, "stageId")), true);
                case "first" -> stageService.getFirstStageByTournamentId(
                    McpToolSupport.requiredLong(arguments, "tournamentId"));
                case "flow" -> stageService.getFlowByTournamentId(
                    McpToolSupport.requiredLong(arguments, "tournamentId"),
                    McpToolSupport.optionalLong(arguments, "stageId"));
                case "prebracket" -> stageService.getPreBracket(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "circle_labels" -> circleLabelService.labels(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "initialize" -> {
                    InitializeStageBo bo = McpToolSupport.requiredObject(
                        arguments, "data", InitializeStageBo.class);
                    lifecycleService.initialize(bo);
                    yield Map.of("initialized", true);
                }
                case "generate_matches" -> {
                    GenerateMatchesBo bo = McpToolSupport.requiredObject(
                        arguments, "data", GenerateMatchesBo.class);
                    lifecycleService.generateMatches(bo);
                    yield Map.of("generated", true);
                }
                case "ensure_circles" -> {
                    lifecycleService.ensureAuditionCircles(
                        McpToolSupport.requiredLong(arguments, "stageId"));
                    yield Map.of("ensured", true);
                }
                case "append_competitor" -> {
                    lifecycleService.appendStageCompetitor(
                        McpToolSupport.requiredLong(arguments, "stageId"),
                        McpToolSupport.requiredLong(arguments, "competitorId"),
                        McpToolSupport.optionalLong(arguments, "matchId"),
                        McpToolSupport.optionalInt(arguments, "zoneIndex")
                    );
                    yield Map.of("appended", true);
                }
                case "withdraw_competitor" -> {
                    lifecycleService.withdrawArenaCompetitor(
                        McpToolSupport.requiredLong(arguments, "stageId"),
                        McpToolSupport.requiredLong(arguments, "competitorId"));
                    yield Map.of("withdrawn", true);
                }
                case "temp_withdraw_competitor" -> {
                    lifecycleService.tempWithdrawArenaCompetitor(
                        McpToolSupport.requiredLong(arguments, "stageId"),
                        McpToolSupport.requiredLong(arguments, "competitorId"));
                    yield Map.of("withdrawn", true);
                }
                case "start" -> {
                    lifecycleService.startStage(McpToolSupport.requiredLong(arguments, "stageId"));
                    yield Map.of("started", true);
                }
                case "complete" -> lifecycleService.completeStage(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "reset_to_draft" -> {
                    lifecycleService.resetStageToDraft(
                        McpToolSupport.requiredLong(arguments, "stageId"));
                    yield Map.of("reset", true);
                }
                case "arena_next" -> {
                    lifecycleService.startNextArenaMatch(
                        McpToolSupport.requiredLong(arguments, "stageId"));
                    yield Map.of("started", true);
                }
                case "arena_overview" -> lifecycleService.getArenaOverview(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "audition_result" -> lifecycleService.queryAuditionResult(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "rank_detail" -> lifecycleService.getRankDetail(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                case "seed_order" -> seedOrder(arguments);
                case "adjust_advancement" -> lifecycleService.adjustAdvancement(
                    McpToolSupport.requiredLong(arguments, "stageId"),
                    McpToolSupport.longList(arguments, "competitorIds"));
                case "confirm_roster" -> lifecycleService.confirmStageRoster(
                    McpToolSupport.requiredLong(arguments, "stageId"));
                default -> throw new ServiceException("不支持的 action: {}", action);
            };
            return McpToolSupport.result("manage_stages", action, result, arguments);
        } catch (Exception error) {
            return McpToolSupport.failure("manage_stages", action,
                request.arguments() == null ? Map.of() : request.arguments(), error);
        }
    }

    private Object list(Map<String, Object> arguments) {
        TStageBo bo = McpToolSupport.optionalObject(arguments, "data", TStageBo.class);
        bo = bo == null ? new TStageBo() : bo;
        Long tournamentId = McpToolSupport.optionalLong(arguments, "tournamentId");
        if (tournamentId != null) {
            bo.setTournamentId(tournamentId);
        }
        var page = stageService.queryPageList(bo, McpToolSupport.pageQuery(arguments));
        List<TStageVo> stages = page.getData();
        if (bo.getTournamentId() != null && stages != null && !stages.isEmpty()) {
            boolean skipConfirm = lifecycleService.isAutoConfirmAdvancement(bo.getTournamentId());
            stages.forEach(stage -> stage.setSkipConfirm(skipConfirm));
            stageService.fillAwaitingAdvancement(stages);
        }
        return page;
    }

    private TStageVo create(Map<String, Object> arguments) {
        TStageBo bo = McpToolSupport.requiredObject(arguments, "data", TStageBo.class);
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return stage;
    }

    private TStageVo updateConfig(Map<String, Object> arguments) {
        TStageConfigBo bo = McpToolSupport.requiredObject(arguments, "data", TStageConfigBo.class);
        bo.setId(McpToolSupport.requiredLong(arguments, "stageId"));
        TStageVo stage = stageService.updateConfig(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return stage;
    }

    private int seedOrder(Map<String, Object> arguments) {
        SeedOrderBo bo = McpToolSupport.optionalObject(arguments, "data", SeedOrderBo.class);
        bo = bo == null ? new SeedOrderBo() : bo;
        bo.setStageId(McpToolSupport.requiredLong(arguments, "stageId"));
        List<Long> competitorIds = McpToolSupport.longList(arguments, "competitorIds");
        if (!competitorIds.isEmpty()) {
            bo.setCompetitorIds(competitorIds);
        }
        return lifecycleService.setSeedOrder(bo);
    }
}
