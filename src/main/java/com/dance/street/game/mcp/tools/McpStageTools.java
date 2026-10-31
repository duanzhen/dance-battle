package com.dance.street.game.mcp.tools;

import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.SnowflakeJson;
import com.dance.street.game.mcp.IMcpTool;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
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
        "circle_labels", "list_circles", "add_circle", "initialize", "generate_matches", "ensure_circles",
        "append_competitor", "withdraw_competitor", "temp_withdraw_competitor",
        "start", "complete", "reset_to_draft",
        "arena_next", "arena_overview", "audition_result", "rank_detail", "seed_order",
        "adjust_advancement", "confirm_roster"
    );
    private static final Set<String> STAGE_MODES = Set.of(
        "AUDITION", "KNOCKOUT", "ARENA", "RANK", "FREE_MATCH"
    );

    private final ITStageService stageService;
    private final ITStageLifecycleService lifecycleService;
    private final CircleLabelService circleLabelService;
    private final AuditionCircleSupport auditionCircleSupport;
    private final ITRefereeStageService refereeStageService;
    private final TStageMapper stageMapper;

    public McpStageTools(ITStageService stageService,
                         ITStageLifecycleService lifecycleService,
                         CircleLabelService circleLabelService,
                         AuditionCircleSupport auditionCircleSupport,
                         ITRefereeStageService refereeStageService,
                         TStageMapper stageMapper) {
        this.stageService = stageService;
        this.lifecycleService = lifecycleService;
        this.circleLabelService = circleLabelService;
        this.auditionCircleSupport = auditionCircleSupport;
        this.refereeStageService = refereeStageService;
        this.stageMapper = stageMapper;
    }

    @Override
    public McpSchema.Tool definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stageId", McpToolSupport.idProperty("Stage id."));
        properties.put("tournamentId", McpToolSupport.idProperty("Tournament id."));
        properties.put("name", Map.of("type", "string",
            "description", "Stage name for create."));
        properties.put("stageMode", Map.of(
            "type", "string",
            "enum", List.copyOf(STAGE_MODES),
            "description", "Stage mode for create."
        ));
        properties.put("teamCountStart", Map.of("type", "integer", "minimum", 0,
            "description", "Planned starting capacity. AUDITION entry stages may use 0."));
        properties.put("teamCountEnd", Map.of("type", "integer", "minimum", 1,
            "description", "Number of competitors advancing from this stage."));
        properties.put("ruleConfig", Map.of("type", "string",
            "description", "Stage rule JSON. AUDITION defaults to one circle when omitted."));
        properties.put("afterStageId", McpToolSupport.idProperty(
            "For create: insert after this existing stage. Required for every non-head stage."));
        properties.put("competitorId", McpToolSupport.idProperty("Competitor id."));
        properties.put("matchId", McpToolSupport.idProperty("Target circle/match id."));
        properties.put("zoneIndex", McpToolSupport.idProperty("One-based circle index when matchId is unknown."));
        properties.put("refereeIds", McpToolSupport.idListProperty(
            "Optional referee ids for the newly added audition circle."));
        properties.put("circleAdvanceCount", Map.of("type", "integer", "minimum", 1,
            "description", "Advancement quota for the newly added circle."));
        properties.put("circleAdvanceCounts", Map.of(
            "type", "array",
            "items", Map.of("type", "integer", "minimum", 1),
            "description", "Optional full per-circle advancement plan for all circles after adding one."
        ));
        properties.put("competitorIds", McpToolSupport.idListProperty(
            "Competitors for seed order or advancement adjustment."));
        properties.put("data", McpToolSupport.objectProperty(
            "TStageBo for create/list, TStageConfigBo for update_config, InitializeStageBo, "
                + "GenerateMatchesBo, or SeedOrderBo depending on action."
        ));
        properties.put("confirmed", Map.of(
            "type", "boolean",
            "description", "For create only: set true after the user explicitly confirmed this stage's "
                + "name, mode, capacity, advancement count, and position in the stage chain."
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
                case "list_circles" -> listCircles(arguments);
                case "add_circle" -> addCircle(arguments);
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
            if (result instanceof McpSchema.CallToolResult toolResult) {
                return toolResult;
            }
            return McpToolSupport.result(action, result);
        } catch (Exception error) {
            return McpToolSupport.failure(action, error);
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

    private Object create(Map<String, Object> arguments) {
        TStageBo bo = McpToolSupport.optionalObject(arguments, "data", TStageBo.class);
        bo = bo == null ? new TStageBo() : bo;
        if (arguments.containsKey("tournamentId")) {
            bo.setTournamentId(McpToolSupport.optionalLong(arguments, "tournamentId"));
        }
        if (arguments.containsKey("name")) {
            bo.setName(McpToolSupport.optionalString(arguments, "name"));
        }
        if (arguments.containsKey("stageMode")) {
            bo.setStageMode(McpToolSupport.optionalString(arguments, "stageMode"));
        }
        if (arguments.containsKey("teamCountStart")) {
            bo.setTeamCountStart(McpToolSupport.optionalLong(arguments, "teamCountStart"));
        }
        if (arguments.containsKey("teamCountEnd")) {
            bo.setTeamCountEnd(McpToolSupport.optionalLong(arguments, "teamCountEnd"));
        }
        if (arguments.containsKey("ruleConfig")) {
            bo.setRuleConfig(McpToolSupport.optionalString(arguments, "ruleConfig"));
        }
        if (arguments.containsKey("afterStageId")) {
            bo.setAfterStageId(McpToolSupport.optionalLong(arguments, "afterStageId"));
        }
        List<String> missing = new java.util.ArrayList<>();
        List<String> questions = new java.util.ArrayList<>();
        if (bo != null) {
            if (bo.getTournamentId() == null) {
                missing.add("data.tournamentId");
                questions.add("请确认这个赛段属于哪个空白赛事。");
            }
            if (bo.getName() == null || bo.getName().isBlank()) {
                missing.add("data.name");
                questions.add("请确认赛段名称。");
            }
            if (bo.getStageMode() == null || bo.getStageMode().isBlank()
                || !STAGE_MODES.contains(bo.getStageMode())) {
                missing.add("data.stageMode");
                questions.add("请选择赛制：AUDITION、KNOCKOUT、ARENA、RANK 或 FREE_MATCH。");
            }
            if (bo.getTeamCountStart() == null) {
                missing.add("data.teamCountStart");
                questions.add("请确认本赛段计划容纳的起始人数；海选入口可明确填写 0。");
            }
            if (bo.getTeamCountEnd() == null || bo.getTeamCountEnd() < 1) {
                missing.add("data.teamCountEnd");
                questions.add("请确认本赛段最终晋级多少人。");
            }
            if (bo.getTournamentId() != null) {
                TStageBo query = new TStageBo();
                query.setTournamentId(bo.getTournamentId());
                List<TStageVo> existing = stageService.queryList(query);
                if (!existing.isEmpty() && bo.getAfterStageId() == null) {
                    missing.add("data.afterStageId");
                    questions.add("请指定这个赛段排在哪个现有赛段之后，以确认赛段顺序。");
                }
                Long afterStageId = bo.getAfterStageId();
                if (afterStageId != null && existing.stream()
                    .noneMatch(stage -> afterStageId.equals(stage.getId()))) {
                    missing.add("data.afterStageId");
                    questions.add("指定的前置赛段不属于当前赛事，请重新确认赛段顺序。");
                }
            }
        }
        if (!Boolean.TRUE.equals(McpToolSupport.optionalBoolean(arguments, "confirmed"))) {
            missing.add("confirmed");
            questions.add("请确认该赛段的名称、赛制、人数、晋级名额和链上位置；确认后才会创建。");
        }
        if (!missing.isEmpty()) {
            return McpToolSupport.clarification("create", missing, questions);
        }
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return McpToolSupport.result("create", stage,
            McpToolSupport.activeContext(stage.getTournamentId(), stage.getId(), null));
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

    private Object listCircles(Map<String, Object> arguments) {
        TStage stage = requireAuditionStage(McpToolSupport.requiredLong(arguments, "stageId"));
        List<Map<String, Object>> circles = auditionCircleSupport.circles(stage).stream()
            .map(this::circleItem)
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stageId", stage.getId());
        result.put("plannedCircles", auditionCircleSupport.plannedCircleCount(stage));
        result.put("createdCircles", circles.size());
        result.put("circles", circles);
        return result;
    }

    private Object addCircle(Map<String, Object> arguments) {
        long stageId = McpToolSupport.requiredLong(arguments, "stageId");
        TStage stage = requireAuditionStage(stageId);
        if (!"DRAFT".equals(stage.getStatus())) {
            throw new ServiceException("仅 DRAFT 状态的海选赛段可以增加圈");
        }
        int oldCount = auditionCircleSupport.plannedCircleCount(stage);
        List<Integer> requestedQuotas = integerList(arguments.get("circleAdvanceCounts"));
        if (!requestedQuotas.isEmpty() && requestedQuotas.size() != oldCount + 1) {
            return McpToolSupport.clarification("add_circle",
                List.of("circleAdvanceCounts"),
                List.of("新增后总圈数为 " + (oldCount + 1)
                    + "，circleAdvanceCounts 必须恰好包含 " + (oldCount + 1) + " 个晋级名额。"));
        }
        if (requestedQuotas.isEmpty()
            && McpToolSupport.optionalInt(arguments, "circleAdvanceCount") == null) {
            return McpToolSupport.clarification("add_circle",
                List.of("circleAdvanceCount"),
                List.of("新增的第 " + (oldCount + 1) + " 圈计划晋级多少人？"
                    + "如需保留原有总晋级人数，请同时说明每个圈调整后的名额。"));
        }

        List<Integer> quotas = requestedQuotas.isEmpty()
            ? existingCircleQuotas(stage, oldCount)
            : new java.util.ArrayList<>(requestedQuotas);
        if (quotas.isEmpty()) {
            return McpToolSupport.clarification("add_circle",
                List.of("circleAdvanceCounts"),
                List.of("现有总晋级人数无法均分到当前圈数，请提供新增后所有圈的完整晋级名额数组。"));
        }
        if (requestedQuotas.isEmpty()) {
            quotas.add(McpToolSupport.optionalInt(arguments, "circleAdvanceCount"));
        }

        try {
            var mapper = SnowflakeJson.mapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = stage.getRuleConfig() == null
                || stage.getRuleConfig().isBlank()
                ? new LinkedHashMap<>()
                : mapper.readValue(stage.getRuleConfig(), Map.class);
            raw.put("mode", "AUDITION");
            raw.put("circles", oldCount + 1);
            raw.put("circleAdvanceCounts", quotas);
            raw.put("advanceCount", quotas.stream().mapToInt(Integer::intValue).sum());

            List<Long> refereeIds = McpToolSupport.longList(arguments, "refereeIds");
            if (arguments.containsKey("refereeIds")
                || raw.get("circleRefereeIds") instanceof List<?>) {
                List<List<Long>> refereePlan = existingCircleRefereePlan(
                    raw, stageId, oldCount);
                refereePlan.add(arguments.containsKey("refereeIds")
                    ? refereeIds : refereeStageService.getRefereeIdsByStageId(stageId));
                raw.put("circleRefereeIds", refereePlan);
            }

            TStageConfigBo config = new TStageConfigBo();
            config.setId(stageId);
            config.setRuleConfig(mapper.writeValueAsString(raw));
            config.setTeamCountEnd((long) quotas.stream().mapToInt(Integer::intValue).sum());
            stageService.updateConfig(config);
            lifecycleService.ensureAuditionCircles(stageId);

            TStage updated = requireAuditionStage(stageId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("addedCircle", oldCount + 1);
            result.put("plannedCircles", auditionCircleSupport.plannedCircleCount(updated));
            result.put("circleAdvanceCounts", quotas);
            result.put("circles", auditionCircleSupport.circles(updated).stream()
                .map(this::circleItem).toList());
            return McpToolSupport.result("add_circle", result,
                McpToolSupport.activeContext(updated.getTournamentId(), stageId, null));
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            throw new ServiceException("增加海选圈失败: {}", error.getMessage());
        }
    }

    private TStage requireAuditionStage(long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (!"AUDITION".equals(stage.getStageMode())) {
            throw new ServiceException("仅海选赛段支持圈管理");
        }
        return stage;
    }

    private List<Integer> existingCircleQuotas(TStage stage, int count) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = stage.getRuleConfig() == null
                || stage.getRuleConfig().isBlank()
                ? Map.of()
                : SnowflakeJson.mapper().readValue(stage.getRuleConfig(), Map.class);
            List<Integer> configured = integerList(raw.get("circleAdvanceCounts"));
            if (configured.size() >= count) {
                return new java.util.ArrayList<>(configured.subList(0, count));
            }
            long total = stage.getTeamCountEnd() == null ? 0L : stage.getTeamCountEnd();
            if (total <= 0 || total % count != 0) {
                return List.of();
            }
            int perCircle = Math.toIntExact(total / count);
            while (configured.size() < count) {
                configured.add(perCircle);
            }
            return configured;
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<List<Long>> existingCircleRefereePlan(
        Map<String, Object> raw, long stageId, int count) {
        List<List<Long>> plan = new java.util.ArrayList<>();
        Object configured = raw.get("circleRefereeIds");
        if (configured instanceof List<?> rows) {
            for (int i = 0; i < Math.min(count, rows.size()); i++) {
                plan.add(longListValue(rows.get(i)));
            }
        }
        List<Long> stageReferees = refereeStageService.getRefereeIdsByStageId(stageId);
        while (plan.size() < count) {
            plan.add(stageReferees);
        }
        return plan;
    }

    private Map<String, Object> circleItem(TMatch circle) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("matchId", circle.getId());
        item.put("name", circle.getName());
        item.put("displayZone", circle.getDisplayZone());
        item.put("status", circle.getStatus());
        item.put("matchMode", circle.getMatchMode());
        return item;
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> values)) {
            return new java.util.ArrayList<>();
        }
        List<Integer> result = new java.util.ArrayList<>();
        for (Object item : values) {
            if (item == null) {
                continue;
            }
            try {
                result.add(item instanceof Number number
                    ? number.intValue() : Integer.valueOf(item.toString()));
            } catch (NumberFormatException error) {
                throw new ServiceException("circleAdvanceCounts 只能包含整数");
            }
        }
        return result;
    }

    private static List<Long> longListValue(Object value) {
        if (!(value instanceof List<?> values)) {
            return new java.util.ArrayList<>();
        }
        List<Long> result = new java.util.ArrayList<>();
        for (Object item : values) {
            if (item == null) {
                continue;
            }
            try {
                result.add(item instanceof Number number
                    ? number.longValue() : Long.valueOf(item.toString()));
            } catch (NumberFormatException error) {
                throw new ServiceException("circleRefereeIds 只能包含整数");
            }
        }
        return result;
    }
}
