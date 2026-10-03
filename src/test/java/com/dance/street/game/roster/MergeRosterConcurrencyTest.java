package com.dance.street.game.roster;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇合段并发结算:两条来源分支同时「完成赛段」时,汇合段的中间层待落位区不重不漏。
 *
 * <p>背景:多赛段同时进行时,两条分支 A、B 各自结算都会触发
 * {@code rebuildEntriesOfDownstream} 重建同一个汇合段 X 的中间层。而 {@code rebuildEntries}
 * 是"清空 + 按全部来源边重新取人",又跑在各自的结算事务里。这个用例就是来钉死
 * "汇合段的待落位行到底会不会被并发重建搞重复/搞丢"。</p>
 *
 * <p>汇合(X 有两条内部来源边)走的是"待落位区"分支:候选人不带座位号({@code slot = NULL}),
 * 只按人逐行落库。所以这里断言的是"待落位行不重不漏",而不是座位号是否被覆盖。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class MergeRosterConcurrencyTest {

    private static final String DB_PATH = "target/merge-roster-concurrency.db";

    /** 反复多轮,尽量放大两个结算事务的调度抖动 */
    private static final int ROUNDS = 6;

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        // 与生产(SqliteFallbackEnvironmentPostProcessor)同一套并发护栏:WAL + 忙等 + 事务开局取写锁。
        // 不加这些参数的裸 SQLite 在并发写时会直接 SQLITE_BUSY,那是测试环境差异,不是线上口径。
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
    void concurrentBranchSettlementIntoSameMergeStageKeepsHoldingRowsIntact() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            runOneRound(round);
        }
    }

    private void runOneRound(int round) throws Exception {
        Long tid = newTournament("汇合并发-" + round);

        // 链顺序 A → B → X:来源必须排在目标之前(拓扑序),X 是汇合段
        TStageVo a = newKnockoutStage(tid, "分支A" + round, 2, 1, null);
        TStageVo b = newKnockoutStage(tid, "分支B" + round, 2, 1, a.getId());
        TStageVo x = newKnockoutStage(tid, "汇合X" + round, 2, 1, b.getId());

        // B 建段时默认带了一条"链上前驱 A"的来源,会依赖 A;改成纯签到入口,让 A/B 真正互不依赖
        makeIndependentEntry(b);
        // X 此时只有默认的 B→X,再补一条 A→X → 两条内部来源 = 典型汇合
        addSource(x, a);

        insertPending(tid, a.getId(), "A1_" + round, "1", 1);
        insertPending(tid, a.getId(), "A2_" + round, "2", 2);
        insertPending(tid, b.getId(), "B1_" + round, "1", 1);
        insertPending(tid, b.getId(), "B2_" + round, "2", 2);

        // 两条分支各自开赛、打完,各产生 1 名晋级者(A、B 同时处于进行中)
        lifecycleService.startStage(a.getId());
        lifecycleService.startStage(b.getId());
        finishByDirector(matchesOf(a.getId()).get(0).getId());
        finishByDirector(matchesOf(b.getId()).get(0).getId());

        Long aWinner = advancerOf(a.getId());
        Long bWinner = advancerOf(b.getId());
        assertNotNull(aWinner, "分支 A 应有 1 名晋级者");
        assertNotNull(bWinner, "分支 B 应有 1 名晋级者");

        // 同时完成两条分支:两边都会重建汇合段 X 的中间层
        runConcurrently(() -> lifecycleService.completeStage(a.getId()),
            () -> lifecycleService.completeStage(b.getId()));

        assertEquals(StageConstants.STAGE_SETTLED, statusOf(a.getId()), "分支 A 应结算为 SETTLED");
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(b.getId()), "分支 B 应结算为 SETTLED");

        List<TStageRosterEntry> entries = entriesOf(x.getId());
        List<TStageRosterEntry> holding = entries.stream()
            .filter(e -> e.getSlot() == null)
            .toList();
        List<Long> holdingSources = holding.stream()
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull)
            .toList();

        assertEquals(Set.of(aWinner, bWinner), new HashSet<>(holdingSources),
            "第 " + round + " 轮:汇合段待落位区应恰好是两条分支的晋级者");
        assertEquals(holdingSources.size(), new HashSet<>(holdingSources).size(),
            "第 " + round + " 轮:汇合段待落位区不应出现重复的人");
        assertEquals(2, holding.size(),
            "第 " + round + " 轮:汇合段待落位区应恰好 2 行(每人一行)");
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
            assertTrue(Boolean.TRUE.equals(r1.getCompleted()), "分支 A 应结算完成: " + r1.getMessage());
            assertTrue(Boolean.TRUE.equals(r2.getCompleted()), "分支 B 应结算完成: " + r2.getMessage());
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

    /** 把赛段改成"纯签到入口":加一条外部来源,再摘掉指向链上前驱的默认来源 */
    private void makeIndependentEntry(TStageVo stage) {
        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo external = new TStageRosterGroupBo();
        external.setSourceStageId(null);
        external.setResultFilter("ANY");
        external.setFillMode("STREAM");
        external.setQuota(0);
        bo.setGroups(List.of(external));
        rosterService.addGroups(stage.getId(), bo);

        List<Long> chainSources = rosterService.listByTarget(stage.getId()).stream()
            .filter(vo -> vo.getGroups() != null)
            .flatMap(vo -> vo.getGroups().stream())
            .filter(g -> g.getSourceStageId() != null && g.getId() != null)
            .map(TStageRosterGroupBo::getId)
            .toList();
        for (Long gid : chainSources) {
            rosterService.removeGroup(stage.getId(), gid);
        }
    }

    /** 给目标赛段补一条 internal 来源边(sourceStageId → target) */
    private void addSource(TStageVo target, TStageVo source) {
        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(source.getId());
        g.setResultFilter("ADVANCE");
        g.setFillMode("AUTO");
        g.setQuota(0);
        bo.setGroups(List.of(g));
        rosterService.addGroups(target.getId(), bo);
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

    private Long advancerOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()))
            .stream().map(TCompetitor::getId).findFirst().orElse(null);
    }

    private String statusOf(Long stageId) {
        return stageMapper.selectById(stageId).getStatus();
    }

    private List<TStageRosterEntry> entriesOf(Long targetStageId) {
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .orderByAsc(TStageRosterEntry::getSlot)
            .orderByAsc(TStageRosterEntry::getId));
    }
}
