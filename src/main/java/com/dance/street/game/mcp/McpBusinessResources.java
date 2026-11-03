package com.dance.street.game.mcp;

import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITMatchRefereeService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Resources：供 Agent 在执行操作前读取业务规则和当前运行态摘要。
 */
@Component
public class McpBusinessResources {

    public static final String BUSINESS_GUIDE_URI = "dance://guide/business";
    public static final String STAGE_MODES_URI = "dance://guide/stage-modes";
    public static final String ROSTER_DAG_URI = "dance://guide/roster-dag";
    public static final String TOURNAMENT_INDEX_URI = "dance://tournaments";
    public static final String TOURNAMENT_OVERVIEW_TEMPLATE =
        "dance://tournaments/{tournamentId}/overview";
    public static final String STAGE_OVERVIEW_TEMPLATE =
        "dance://stages/{stageId}/overview";

    private static final Pattern TOURNAMENT_OVERVIEW =
        Pattern.compile("^dance://tournaments/(\\d+)/overview$");
    private static final Pattern STAGE_OVERVIEW =
        Pattern.compile("^dance://stages/(\\d+)/overview$");

    private final ITTournamentService tournamentService;
    private final ITStageService stageService;
    private final ITStageRosterService rosterService;
    private final ITMatchService matchService;
    private final ITCompetitorService competitorService;
    private final ITRefereeStageService refereeStageService;
    private final ITMatchRefereeService matchRefereeService;

    public McpBusinessResources(ITTournamentService tournamentService,
                                ITStageService stageService,
                                ITStageRosterService rosterService,
                                ITMatchService matchService,
                                ITCompetitorService competitorService,
                                ITRefereeStageService refereeStageService,
                                ITMatchRefereeService matchRefereeService) {
        this.tournamentService = tournamentService;
        this.stageService = stageService;
        this.rosterService = rosterService;
        this.matchService = matchService;
        this.competitorService = competitorService;
        this.refereeStageService = refereeStageService;
        this.matchRefereeService = matchRefereeService;
    }

    public List<McpStatelessServerFeatures.SyncResourceSpecification> resources() {
        return List.of(
            resource(BUSINESS_GUIDE_URI, "business-guide", "Dance Battle business guide",
                "Read-before-write rules, the two tournament creation modes, stable IDs, and tool selection.",
                BUSINESS_GUIDE),
            resource(STAGE_MODES_URI, "stage-modes", "Stage mode reference",
                "Differences between AUDITION, KNOCKOUT, ARENA, RANK and FREE_MATCH.",
                STAGE_MODES_GUIDE),
            resource(ROSTER_DAG_URI, "roster-dag", "Roster DAG reference",
                "Stage-chain topology, directed roster edges, intermediate roster rows and propagation.",
                ROSTER_DAG_GUIDE),
            new McpStatelessServerFeatures.SyncResourceSpecification(
                jsonResource(TOURNAMENT_INDEX_URI, "tournament-index", "Tournament index",
                    "Current tournament IDs, names, statuses and stage names."),
                this::readTournamentIndex)
        );
    }

    public List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
        return List.of(
            new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder(TOURNAMENT_OVERVIEW_TEMPLATE, "tournament-overview")
                    .title("Tournament operating overview")
                    .description("Live tournament summary: stages in chain order, roster readiness, "
                        + "match state counts and current running match.")
                    .mimeType("application/json")
                    .build(),
                this::readTournamentOverview),
            new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder(STAGE_OVERVIEW_TEMPLATE, "stage-overview")
                    .title("Stage operating overview")
                    .description("Live stage summary: configuration, incoming roster state, competitors, "
                        + "referee assignments and matches.")
                    .mimeType("application/json")
                    .build(),
                this::readStageOverview)
        );
    }

    private McpStatelessServerFeatures.SyncResourceSpecification resource(
        String uri, String name, String title, String description, String text) {
        return new McpStatelessServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder(uri, name)
                .title(title)
                .description(description)
                .mimeType("text/markdown")
                .build(),
            (context, request) -> text(uri, "text/markdown", text)
        );
    }

    private static McpSchema.Resource jsonResource(String uri, String name,
                                                    String title, String description) {
        return McpSchema.Resource.builder(uri, name)
            .title(title)
            .description(description)
            .mimeType("application/json")
            .build();
    }

    private McpSchema.ReadResourceResult readTournamentIndex(
        McpTransportContext context, McpSchema.ReadResourceRequest request) {
        var page = tournamentService.queryPageList(
            new com.dance.street.game.domain.bo.TTournamentBo(),
            new PageQuery(100, 1));
        List<Map<String, Object>> tournaments = page.getData() == null
            ? List.of()
            : page.getData().stream().map(tournament -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", tournament.getId());
                item.put("name", tournament.getName());
                item.put("status", tournament.getStatus());
                item.put("stageNames", tournament.getStageNames());
                item.put("createTime", tournament.getCreateTime());
                return item;
            }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("total", page.getTotal());
        result.put("returned", tournaments.size());
        result.put("tournaments", tournaments);
        return json(TOURNAMENT_INDEX_URI, result);
    }

    private McpSchema.ReadResourceResult readTournamentOverview(
        McpTransportContext context, McpSchema.ReadResourceRequest request) {
        long tournamentId = requiredUriId(TOURNAMENT_OVERVIEW, request.uri());
        var tournament = tournamentService.queryById(tournamentId);
        if (tournament == null) {
            throw notFound(request.uri());
        }

        TStageBo stageQuery = new TStageBo();
        stageQuery.setTournamentId(tournamentId);
        List<TStageVo> stages = stageService.queryList(stageQuery);
        stageService.fillAwaitingAdvancement(stages);
        Map<Long, List<TStageRosterVo>> rosters =
            rosterService.listByTargets(stages.stream().map(TStageVo::getId).toList());

        TMatchBo matchQuery = new TMatchBo();
        matchQuery.setTournamentId(tournamentId);
        List<TMatchVo> matches = matchService.queryList(matchQuery);
        Map<Long, Map<String, Long>> matchCounts = countMatchesByStage(matches);
        StageFlowVo flow = stageService.getFlowByTournamentId(tournamentId, null);

        List<Map<String, Object>> stageItems = new ArrayList<>();
        for (TStageVo stage : stages) {
            Map<String, Object> item = compactStage(stage);
            item.put("matchCounts", matchCounts.getOrDefault(stage.getId(), Map.of()));
            var roster = firstRoster(rosters.get(stage.getId()));
            item.put("rosterState", roster == null ? null : roster.getState());
            item.put("incomingSources", roster == null ? List.of()
                : roster.getGroups().stream().map(group -> group.getSourceStageId())
                    .filter(Objects::nonNull).distinct().toList());
            stageItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("tournament", tournamentSummary(tournament));
        result.put("flow", flowSummary(flow));
        result.put("stages", stageItems);
        return json(request.uri(), result);
    }

    private McpSchema.ReadResourceResult readStageOverview(
        McpTransportContext context, McpSchema.ReadResourceRequest request) {
        long stageId = requiredUriId(STAGE_OVERVIEW, request.uri());
        TStageVo stage = stageService.queryById(stageId);
        if (stage == null) {
            throw notFound(request.uri());
        }
        var rosters = rosterService.listByTargets(List.of(stageId));
        var roster = firstRoster(rosters.get(stageId));
        StageParticipantsVo participants = rosterService.listStageParticipants(stageId);

        TCompetitorBo competitorQuery = new TCompetitorBo();
        competitorQuery.setStageId(stageId);
        List<Map<String, Object>> competitors = competitorService.queryList(competitorQuery)
            .stream().map(McpBusinessResources::compactCompetitor).toList();

        TMatchBo matchQuery = new TMatchBo();
        matchQuery.setStageId(stageId);
        List<TMatchVo> stageMatches = matchService.queryList(matchQuery);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("stage", compactStage(stage));
        result.put("roster", rosterSummary(roster));
        result.put("participantSummary", participantSummary(participants));
        result.put("competitors", competitors);
        result.put("refereeIds", refereeStageService.getRefereeIdsByStageId(stageId));
        result.put("matchReferees", matchRefereeService.listByStageId(stageId));
        result.put("matches", stageMatches.stream().map(McpBusinessResources::compactMatch).toList());
        result.put("matchCounts", countMatchesByStage(stageMatches)
            .getOrDefault(stageId, Map.of()));
        return json(request.uri(), result);
    }

    private static Map<String, Object> tournamentSummary(
        com.dance.street.game.domain.vo.TTournamentVo tournament) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", tournament.getId());
        item.put("name", tournament.getName());
        item.put("status", tournament.getStatus());
        item.put("description", tournament.getDescription());
        item.put("logicalWidth", tournament.getLogicalWidth());
        item.put("logicalHeight", tournament.getLogicalHeight());
        item.put("playerCount", tournament.getPlayerCount());
        return item;
    }

    private static Map<String, Object> compactStage(TStageVo stage) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", stage.getId());
        item.put("name", stage.getName());
        item.put("stageMode", stage.getStageMode());
        item.put("status", stage.getStatus());
        item.put("teamCountStart", stage.getTeamCountStart());
        item.put("teamCountEnd", stage.getTeamCountEnd());
        item.put("isInitialized", stage.getIsInitialized());
        item.put("prevStageId", stage.getPrevStageId());
        item.put("nextStageId", stage.getNextStageId());
        item.put("awaitingAdvancement", stage.getAwaitingAdvancement());
        item.put("skipConfirm", stage.getSkipConfirm());
        return item;
    }

    private static Map<String, Object> flowSummary(StageFlowVo flow) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("currentStageId", flow.getCurrentStageId());
        item.put("currentMatch", flow.getCurrentMatch());
        item.put("currentMatchParticipants", flow.getCurrentMatchParticipants());
        return item;
    }

    private static Map<String, Object> rosterSummary(
        TStageRosterVo roster) {
        if (roster == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("state", roster.getState());
        item.put("groups", roster.getGroups());
        item.put("overrides", roster.getOverrides());
        return item;
    }

    private static Map<String, Object> participantSummary(StageParticipantsVo participants) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", participants.getSource());
        item.put("capacity", participants.getCapacity());
        item.put("seatedCount", participants.getSeatedCount());
        item.put("holdingCount", participants.getHoldingCount());
        item.put("items", participants.getItems());
        return item;
    }

    private static Map<String, Object> compactCompetitor(TCompetitorVo competitor) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", competitor.getId());
        item.put("name", competitor.getName());
        item.put("number", competitor.getNumber());
        item.put("seedRank", competitor.getSeedRank());
        item.put("finalRank", competitor.getFinalRank());
        item.put("outcomeStatus", competitor.getOutcomeStatus());
        item.put("sourceStageId", competitor.getSourceStageId());
        return item;
    }

    private static Map<String, Object> compactMatch(TMatchVo match) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", match.getId());
        item.put("name", match.getName());
        item.put("status", match.getStatus());
        item.put("matchMode", match.getMatchMode());
        item.put("displayZone", match.getDisplayZone());
        item.put("leftCompetitorId", match.getLeftCompetitorId());
        item.put("rightCompetitorId", match.getRightCompetitorId());
        item.put("leftSlotKind", match.getLeftSlotKind());
        item.put("rightSlotKind", match.getRightSlotKind());
        item.put("winnerName", match.getWinnerName());
        item.put("pendingPublish", match.getPendingPublish());
        item.put("tiebreakerRound", match.getTiebreakerRound());
        return item;
    }

    private static Map<Long, Map<String, Long>> countMatchesByStage(List<TMatchVo> matches) {
        Map<Long, Map<String, Long>> result = new LinkedHashMap<>();
        for (TMatchVo match : matches) {
            if (match.getStageId() == null) {
                continue;
            }
            result.computeIfAbsent(match.getStageId(), ignored -> new LinkedHashMap<>())
                .merge(Objects.toString(match.getStatus(), "UNKNOWN"), 1L, Long::sum);
        }
        return result;
    }

    private static TStageRosterVo firstRoster(List<TStageRosterVo> rosters) {
        return rosters == null || rosters.isEmpty() ? null : rosters.getFirst();
    }

    private static long requiredUriId(Pattern pattern, String uri) {
        Matcher matcher = pattern.matcher(uri == null ? "" : uri);
        if (!matcher.matches()) {
            throw notFound(uri);
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException error) {
            throw notFound(uri);
        }
    }

    private static McpError notFound(String uri) {
        return McpError.RESOURCE_NOT_FOUND.apply("Resource not found: " + uri);
    }

    private static McpSchema.ReadResourceResult text(String uri, String mimeType, String text) {
        return new McpSchema.ReadResourceResult(List.of(
            new McpSchema.TextResourceContents(uri, mimeType, text)
        ));
    }

    private static McpSchema.ReadResourceResult json(String uri, Object value) {
        return text(uri, "application/json", JsonUtils.toJsonString(value));
    }

    private static final String BUSINESS_GUIDE = """
        # Dance Battle MCP operating guide

        ## Read before writing
        - Read the tournament index and the relevant tournament/stage overview before choosing IDs.
        - Never infer IDs by name when duplicate names are possible.
        - Ask the user before any write whose material parameters are unspecified.
        - Do not copy a similar tournament, use temporary names, or invent a stage plan.

        ## Tournament creation
        1. Ask for the tournament name, template code, and referee decision.
        2. Every tournament uses `create_from_template` with one `templateCode`:
           BLANK, AUDITION_32, AUDITION_16, or AUDITION_ARENA.
        3. BLANK creates only a tournament draft. For other templates, the full stage chain is generated.
           Before `manage_stages.create` on a blank tournament, ask for each stage's order, name, `stageMode`,
           `teamCountStart`, `teamCountEnd`, and `afterStageId` for non-head stages.
        4. Configure at least one `refereeNames` entry, or pass `noRefereeConfirmed=true`
           only after the user explicitly confirms that there are no referees.
        5. Send `confirmed=true` to `manage_stages.create` only after the user confirmed the stage values.
        6. `clarification_required` means stop and ask the listed questions.

        ## Main tools
        - `manage_tournaments`: tournament CRUD and unified creation through `create_from_template`.
        - `manage_stages`: stage chain, initialization, generation, execution and settlement.
        - `manage_rosters`: source edges, intermediate seats, overrides and roster confirmation.
        - `manage_players`: player profiles, check-in and import.
          Import uses `tournamentId` plus `players=[{name,idCard?,tags?,remark?}]`.
          Player and Competitor are separate domains. Import creates player records only.
        - `manage_competitors`: stage Competitor entries, member links and match participants.
          Use `register_player` for one Player, or `register_players` with an `entries[]` array for a batch.
          CREATE requires `competitorNumber`; JOIN requires `competitorId`. The batch response reports per-entry errors.
          AUDITION defaults to one circle and creates it automatically. Multi-circle entry requires
          `matchId` or `zoneIndex`.
          `cancel_player_entry` removes the entry relation without deleting the Player.
        - `manage_referees`: referee setup, assignment and scoring.
        - `manage_matches`: matches, rounds, scores and live execution.
        - `manage_displays`: scenes, widgets, screens and projection.

        ## Safety
        - Prefer lifecycle actions over direct updates to generated participants/results.
        - Confirm before delete, reset, skip, override, publish, or any action whose target list is unclear.
        - Use the stage overview resource to locate the actual next action.
        """;

    private static final String STAGE_MODES_GUIDE = """
        # Stage modes

        - AUDITION: per-competitor scoring in ZONE circles. Players must be placed explicitly.
          Completion uses per-circle quotas; cutoff ties create tiebreak matches or manual designation.
        - RANK: all competitors in one ranking match with multi-referee, multi-dimension scores.
          Missing judges block completion. Cutoff ties remain PENDING for `adjust_advancement`.
        - KNOCKOUT: one stage equals one elimination round. Generate only the first-round bracket,
          preserve BYE seats, route winners forward, and support BO series and an optional third-place match.
        - ARENA: no generated bracket. Start creates the first queue match; winner stays, loser goes to tail,
          draw sends both to tail. Permanent and temporary withdrawal are different operations.
        - FREE_MATCH: no generated matches. While GAMING, add two-competitor matches manually,
          score each match, then explicitly choose any number of advancers in seed order.
        """;

    private static final String ROSTER_DAG_GUIDE = """
        # Stage chain, roster DAG and intermediate roster

        - `nextStageId` defines the display/topological order.
        - Each roster group is a directed edge `sourceStageId -> targetStageId`.
          Edges only point forward in chain order, so branches and merges form a DAG.
        - A source edge selects competitors by result, rank, zone, round, score and quota.
        - `t_stage_roster_entry` is the working roster:
          `PLAYER` is a person, `BYE` is a settled empty seat, `PENDING` is an undecided empty seat.
        - One inbound edge auto-seats by source rank. Multiple inbound edges send people to HOLDING;
          the operator must place every person before confirmation.
        - Match completion can incrementally synchronize affected pre-advancers.
          Stage completion reconciles direct downstream targets while preserving manual placement.
        - `apply/confirm` copies the intermediate roster into Competitor rows and sets `rosterApplied=1`.
        - Merge targets wait for all inbound sources to settle before confirmation/start.
        - Undo downstream before upstream. Never mutate a locked downstream roster implicitly.
        """;
}
