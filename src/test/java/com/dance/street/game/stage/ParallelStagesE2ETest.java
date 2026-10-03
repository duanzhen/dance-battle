package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多赛段同时进行的全链路:上游分叉 → 两条分支并行开赛/结算 → 汇合段收齐两条分支的晋级者。
 *
 * <pre>
 *   上游 S(4进2) ──┬─ 分支A(第1名) ─┐
 *                   └─ 分支B(第2名) ─┴─ 汇合 X
 * </pre>
 *
 * <p>验证三件在多段并行下必须成立的事:</p>
 * <ol>
 *   <li><b>并行开赛</b>:A、B 的依赖各自是 S(A 的链上前驱是 S,B 已被改成也依赖 S),
 *       所以 A 进行中不会挡住 B,B 能在 A 还是 GAMING 时正常开赛;</li>
 *   <li><b>并行结算</b>:A、B 同时完成赛段,互不影响;</li>
 *   <li><b>汇合</b>:X 同时收 A、B 两条来源,X 的中间层待落位区恰好是两人的晋级者,不重不漏,
 *       且在确认名单前不允许直接开赛。</li>
 * </ol>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ParallelStagesE2ETest {

    private static final String DB_PATH = "target/parallel-stages-e2e.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:sqlite:" + DB_PATH
                + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS"
                + "&journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE&synchronous=NORMAL");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TStageRosterEntryMapper entryMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITStageRosterService rosterService;

    @Test
    void forkBranchesRunInParallelThenMergeTogether() throws Exception {
        Long tid = newTournament("并行分叉汇合");
        // 链顺序:S → A → B → X(来源必须在目标之前;依赖由来源组决定,不由链决定)
        TStageVo s = newKnockoutStage(tid, "上游", 4, 2, null);
        TStageVo a = newKnockoutStage(tid, "分支A", 2, 1, s.getId());
        TStageVo b = newKnockoutStage(tid, "分支B", 2, 1, a.getId());
        TStageVo x = newKnockoutStage(tid, "汇合", 2, 1, b.getId());

        // 分叉:S 的胜者 → A、败者 → B(B 原本默认依赖 A,替换成依赖 S,让 A/B 真正并行)
        setOnlySource(a, s, OutcomeStatusEnum.ADVANCE.getCode());
        setOnlySource(b, s, OutcomeStatusEnum.ELIMINATED.getCode());
        // 汇合:X 默认已有 B→X,补一条 A→X
        addSource(x, a);

        // ---- 上游 S 跑完:S 的 4 人报名,两场打完,2 人晋级并写出名次 1/2 ----
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, s.getId(), "S" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(s.getId());
        List<TMatch> sMatches = matchesOf(s.getId());
        assertEquals(2, sMatches.size(), "4 进 2 应有两场首轮");
        for (TMatch m : sMatches) {
            finishByDirector(m.getId());
        }
        lifecycleService.completeStage(s.getId());
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(s.getId()));
        assertEquals(2, countOutcome(s.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        // ---- 两条分支并行:各自确认名单后开赛 ----
        lifecycleService.confirmStageRoster(a.getId());
        lifecycleService.confirmStageRoster(b.getId());
        Set<Long> aComps = sourceCompetitorIdsOf(a.getId());
        Set<Long> bComps = sourceCompetitorIdsOf(b.getId());
        assertEquals(advancersOf(s.getId()), aComps, "分支A 应拿到上游的胜者");
        assertEquals(eliminatedOf(s.getId()), bComps, "分支B 应拿到上游的败者");
        assertTrue(java.util.Collections.disjoint(aComps, bComps), "两条分支的人不应重叠");

        lifecycleService.startStage(a.getId());
        assertEquals(StageConstants.STAGE_GAMING, statusOf(a.getId()));
        // 关键:A 进行中不影响 B —— B 的来源是 S(已结算),不是 A
        assertEquals(StageConstants.STAGE_DRAFT, statusOf(b.getId()),
            "A 开赛不应连带改变 B 的状态");
        lifecycleService.startStage(b.getId());
        assertEquals(StageConstants.STAGE_GAMING, statusOf(a.getId()));
        assertEquals(StageConstants.STAGE_GAMING, statusOf(b.getId()), "A、B 应能同时进行中");

        // 每条分支 2 人 1 场,打完产生各自唯一的晋级者
        finishByDirector(matchesOf(a.getId()).get(0).getId());
        finishByDirector(matchesOf(b.getId()).get(0).getId());

        // ---- 两条分支并行结算 ----
        runConcurrently(() -> lifecycleService.completeStage(a.getId()),
            () -> lifecycleService.completeStage(b.getId()));
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(a.getId()));
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(b.getId()));

        // ---- 汇合段 X 收齐两条分支的晋级者 ----
        Long aWinner = advancerOf(a.getId());
        Long bWinner = advancerOf(b.getId());
        assertNotNull(aWinner);
        assertNotNull(bWinner);

        List<TStageRosterEntry> holding = entriesOf(x.getId()).stream()
            .filter(e -> e.getSlot() == null)
            .toList();
        List<Long> sources = holding.stream()
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull)
            .toList();
        assertEquals(Set.of(aWinner, bWinner), new HashSet<>(sources),
            "汇合段待落位区应恰好是两条分支的晋级者");
        assertEquals(sources.size(), new HashSet<>(sources).size(), "汇合段不应出现重复的人");
        assertEquals(2, holding.size(), "汇合段应有且仅有 2 条待落位行");

        // 汇合段是人未落座的中间态,直接开赛应被守卫拦下(需先确认名单)
        assertThrows(ServiceException.class, () -> lifecycleService.startStage(x.getId()),
            "待落位名单未确认时不允许直接开赛");
    }

    /**
     * 多入口汇合里"部分来源还没开赛":已结算来源的晋级者必须立刻出现在待落位区,
     * 不能因为另一条来源还没开赛就把整段留空(否则"某圈 1~8 直进下一段"的人永远看不到)。
     */
    @Test
    void partiallySettledSourcesStillMaterializeKnownAdvancers() {
        Long tid = newTournament("部分来源就绪的汇合");
        TStageVo settled = newKnockoutStage(tid, "已结算来源", 4, 2, null);
        TStageVo fresh = newKnockoutStage(tid, "未开赛来源", 4, 2, settled.getId());
        TStageVo merge = newKnockoutStage(tid, "汇合", 4, 2, fresh.getId());
        // 汇合段同时依赖「已结算来源」与「还没开赛的来源」
        setOnlySource(merge, settled, OutcomeStatusEnum.ADVANCE.getCode());
        addSource(merge, fresh);

        for (int i = 1; i <= 4; i++) {
            insertPending(tid, settled.getId(), "S" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(settled.getId());
        for (TMatch m : matchesOf(settled.getId())) {
            finishByDirector(m.getId());
        }
        lifecycleService.completeStage(settled.getId());
        Set<Long> advancers = advancersOf(settled.getId());
        assertEquals(2, advancers.size());

        List<TStageRosterEntry> holding = entriesOf(merge.getId()).stream()
            .filter(e -> e.getSlot() == null)
            .toList();
        Set<Long> sources = holding.stream()
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(sources.containsAll(advancers),
            "已结算来源的晋级者应已进入汇合段待落位区,实际:" + sources);
    }

    // ------------------------------------------------------------------

    private void runConcurrently(Callable<StageCompleteVo> first, Callable<StageCompleteVo> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<StageCompleteVo> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return first.call();
            });
            Future<StageCompleteVo> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return second.call();
            });
            StageCompleteVo r1 = f1.get(30, TimeUnit.SECONDS);
            StageCompleteVo r2 = f2.get(30, TimeUnit.SECONDS);
            assertTrue(Boolean.TRUE.equals(r1.getCompleted()), "分支A 应结算完成: " + r1.getMessage());
            assertTrue(Boolean.TRUE.equals(r2.getCompleted()), "分支B 应结算完成: " + r2.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newKnockoutStage(Long tid, String name, int teams, int advance, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) teams);
        bo.setTeamCountEnd((long) advance);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\"},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageService.insertByBo(bo);
    }

    /**
     * 把目标赛段的来源组换成"只有 source 一条,且按名次段 [rankStart,rankEnd] 取人"。
     * 用于把默认的链式衔接替换成人工配的分叉出口。
     */
    private void setOnlySource(TStageVo target, TStageVo source, String resultFilter) {
        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(source.getId());
        g.setResultFilter(resultFilter);
        g.setFillMode("AUTO");
        g.setQuota(0);
        bo.setGroups(List.of(g));
        rosterService.addGroups(target.getId(), bo);

        for (TStageRosterGroupBo existing : allGroups(target)) {
            boolean matches = Objects.equals(existing.getSourceStageId(), source.getId())
                && Objects.equals(existing.getResultFilter(), resultFilter);
            if (!matches) {
                rosterService.removeGroup(target.getId(), existing.getId());
            }
        }
    }

    /** 给目标赛段补一条 internal 来源边(source → target,整单晋级) */
    private void addSource(TStageVo target, TStageVo source) {
        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(source.getId());
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode("AUTO");
        g.setQuota(0);
        bo.setGroups(List.of(g));
        rosterService.addGroups(target.getId(), bo);
    }

    private List<TStageRosterGroupBo> allGroups(TStageVo stage) {
        return rosterService.listByTarget(stage.getId()).stream()
            .filter(vo -> vo.getGroups() != null)
            .flatMap(vo -> vo.getGroups().stream())
            .toList();
    }

    private void insertPending(Long tid, Long stageId, String name, String number, int seed) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank((long) seed);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
    }

    private void finishByDirector(Long matchId) {
        matchResultService.startMatch(matchId);
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matchId)
                .isNotNull(TMatchParticipant::getCompetitorId)
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertTrue(parts.size() >= 2, "真人场次应有至少 2 名参赛方");
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            outcomes.put(parts.get(i).getCompetitorId(), i == 0 ? "WIN" : "LOSS");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    /** 某赛段参赛方的"来源参赛方 ID"集合(物化后新行通过 sourceCompetitorId 回指上游) */
    private Set<Long> sourceCompetitorIdsOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .isNotNull(TCompetitor::getSourceCompetitorId))
            .stream().map(TCompetitor::getSourceCompetitorId).collect(java.util.stream.Collectors.toSet());
    }

    /** 某赛段晋级者 ID 集合 */
    private Set<Long> advancersOf(Long stageId) {
        return competitorIdsByOutcome(stageId, OutcomeStatusEnum.ADVANCE.getCode());
    }

    /** 某赛段落选者 ID 集合 */
    private Set<Long> eliminatedOf(Long stageId) {
        return competitorIdsByOutcome(stageId, OutcomeStatusEnum.ELIMINATED.getCode());
    }

    private Set<Long> competitorIdsByOutcome(Long stageId, String outcome) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, outcome))
            .stream().map(TCompetitor::getId).collect(java.util.stream.Collectors.toSet());
    }

    private Long advancerOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()))
            .stream().map(TCompetitor::getId).findFirst().orElse(null);
    }

    private String statusOf(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return stage == null ? null : stage.getStatus();
    }

    private List<TStageRosterEntry> entriesOf(Long targetStageId) {
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .orderByAsc(TStageRosterEntry::getSlot)
            .orderByAsc(TStageRosterEntry::getId));
    }
}
