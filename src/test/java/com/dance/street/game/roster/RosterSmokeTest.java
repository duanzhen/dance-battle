package com.dance.street.game.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名单流转冒烟测试:SQLite 全量上下文 + 真实建表。
 * 覆盖:新建赛段自动合成默认名单 → 源赛段结算后名单就绪 →
 * applyRoster 复制行/种子/溯源/applied → 幂等。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RosterSmokeTest {

    private static final String DB_PATH = "target/roster-smoke.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:sqlite:" + DB_PATH
                + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private javax.sql.DataSource dataSource;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageRosterService rosterService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private TMatchMapper matchMapper;
    @Autowired
    private TMatchParticipantMapper participantMapper;

    @Test
    void defaultRosterSynthesisAndApplyRoster() {
        TTournament tournament = new TTournament();
        tournament.setName("名单冒烟赛事");
        tournamentMapper.insert(tournament);

        TStageVo stage1 = stageService.insertByBo(baseStage(tournament.getId(), "海选", "AUDITION", 0L, 2L, null));
        TStageVo stage2 = stageService.insertByBo(
            baseStage(tournament.getId(), "32强", "KNOCKOUT", 2L, 1L, stage1.getId()));
        // 直接用 mapper 补 ruleConfig,避免 updateByBo 因缺 prev/next 指针清理链表
        TStage stage2Rule = new TStage();
        stage2Rule.setId(stage2.getId());
        stage2Rule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"FINAL\",\"teamsCount\":2,\"advanceCount\":1,\"format\":\"BO1\",\"pairingMode\":\"SEQUENTIAL\"}}");
        stageMapper.updateById(stage2Rule);

        // 1) 建赛段自动合成默认名单(source=stage1, ADVANCE, AUTO)
        List<TStageRosterVo> rosters = rosterService.listByTarget(stage2.getId());
        assertEquals(1, rosters.size(), "目标赛段应有 1 条默认名单");
        TStageRosterVo roster = rosters.get(0);
        assertEquals(stage1.getId(), roster.getSourceStageId());
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), roster.getResultFilter());
        assertEquals(RosterConstants.FILL_AUTO, roster.getFillMode());
        assertEquals(RosterConstants.ROSTER_WAIT_SOURCE, roster.getState(), "源赛段未结算时应为 WAIT_SOURCE");

        // 1b) stage list 返回 incoming 摘要
        TStageBo query = new TStageBo();
        query.setTournamentId(tournament.getId());
        TStageVo stage2WithIncoming = stageService.queryList(query).stream()
            .filter(s -> s.getId().equals(stage2.getId()))
            .findFirst()
            .orElseThrow();
        assertNotNull(stage2WithIncoming.getIncoming());
        assertEquals(1, stage2WithIncoming.getIncoming().size());
        assertEquals(stage1.getId(), stage2WithIncoming.getIncoming().get(0).getSourceStageId());

        // 2) 源赛段产生两名晋级者并结算
        insertCompetitor(tournament.getId(), stage1.getId(), "选手A", "1", 1L);
        insertCompetitor(tournament.getId(), stage1.getId(), "选手B", "2", 2L);
        TStage settled = new TStage();
        settled.setId(stage1.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        // 3) apply 整单装配
        int created = rosterService.applyRoster(stage2.getId(), null);
        assertEquals(2, created, "应复制 2 名晋级者到目标赛段");

        List<TCompetitor> rows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage2.getId())
                .orderByAsc(TCompetitor::getSeedRank));
        assertEquals(2, rows.size());
        assertEquals(RosterConstants.ROSTER_CONFIRMED, rosterService.listByTarget(stage2.getId()).get(0).getState());
        for (TCompetitor row : rows) {
            assertEquals(stage1.getId(), row.getSourceStageId());
            assertEquals(1L, row.getFromRoster().longValue());
            assertEquals(RosterConstants.ENTRY_ADVANCE, row.getEntryTag());
            assertEquals(OutcomeStatusEnum.PENDING.getCode(), row.getOutcomeStatus());
            assertNotNull(row.getSourceCompetitorId());
            assertTrue(row.getSeedRank() == 1L || row.getSeedRank() == 2L);
        }

        // 4) 幂等:再次 apply 不新增
        assertEquals(0, rosterService.applyRoster(stage2.getId(), null));
        assertEquals(2, competitorMapper.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage2.getId())));

        // 5) 默认来源组不应重复合成
        rosterService.ensureRosterForStage(stageMapper.selectById(stage2.getId()));
        assertEquals(1, rosterService.listByTarget(stage2.getId()).get(0).getGroups().size());

        // 6) reset 语义:删除名单快照行,applied 回退
        lifecycleService.resetStageToDraft(stage2.getId());
        assertEquals(0, competitorMapper.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage2.getId())));
        assertEquals(RosterConstants.ROSTER_READY, rosterService.listByTarget(stage2.getId()).get(0).getState());

        // 7a) 名单守卫:未确认时开赛被拦(兼容路径不再误放行)
        ServiceException blocked = assertThrows(ServiceException.class,
            () -> lifecycleService.startStage(stage2.getId()));
        assertTrue(blocked.getMessage().contains("名单尚未确认"));

        // 7b) 写穿路径:导播确认晋级委托名单装配并置 applied
        assertEquals(2, lifecycleService.calculateAdvancement(stage1.getId()));
        List<TCompetitor> advRows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage2.getId()));
        assertEquals(2, advRows.size());
        TStageRosterVo rosterAfter = rosterService.listByTarget(stage2.getId()).get(0);
        assertEquals(RosterConstants.ROSTER_CONFIRMED, rosterAfter.getState());
        for (TCompetitor row : advRows) {
            assertEquals(stage1.getId(), row.getSourceStageId());
            assertEquals(1L, row.getFromRoster().longValue());
            assertEquals(RosterConstants.ENTRY_ADVANCE, row.getEntryTag());
        }
        // 写穿后的行同样满足 apply 幂等
        assertEquals(0, rosterService.applyRoster(stage2.getId(), null));

        // 8) 名单守卫放行:名单已确认后 startStage 可正常开赛并生成对阵
        lifecycleService.startStage(stage2.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage2.getId()).getStatus());
    }

    @Test
    void createRosterDagGuardAndMultiSourceApply() {
        TTournament tournament = new TTournament();
        tournament.setName("多来源装配赛事");
        tournamentMapper.insert(tournament);

        TStageVo stageA = stageService.insertByBo(baseStage(tournament.getId(), "海选", "AUDITION", 0L, 3L, null));
        TStageVo stageB = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 3L, 1L, stageA.getId()));
        TStage stageBRule = new TStage();
        stageBRule.setId(stageB.getId());
        stageBRule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":4,\"advanceCount\":2,\"format\":\"BO1\"}}");
        stageMapper.updateById(stageBRule);

        // DAG 守卫:不能把后置赛段作为来源
        TStageRosterBo invalid = new TStageRosterBo();
        invalid.setSourceStageId(stageB.getId());
        invalid.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        ServiceException dagBlocked = assertThrows(ServiceException.class,
            () -> rosterService.addGroups(stageA.getId(), invalid));
        assertTrue(dagBlocked.getMessage().contains("推进链之前"));

        // 复活名单:target=B, source=A, ELIMINATED
        TStageRosterBo revive = new TStageRosterBo();
        revive.setSourceStageId(stageA.getId());
        revive.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        revive.setFillMode(RosterConstants.FILL_AUTO);
        revive.setQuota(0);
        revive.setPriority(2);
        rosterService.addGroups(stageB.getId(), revive);
        // 收敛模型:同一目标只有一条名单行,新增来源 = 往 groups 追加组
        assertEquals(1, rosterService.listByTarget(stageB.getId()).size());

        // 源赛段:2 名晋级 + 1 名落选
        insertCompetitor(tournament.getId(), stageA.getId(), "晋级A", "1", 1L);
        insertCompetitor(tournament.getId(), stageA.getId(), "晋级B", "2", 2L);
        TCompetitor loser = new TCompetitor();
        loser.setTournamentId(tournament.getId());
        loser.setStageId(stageA.getId());
        loser.setType(0L);
        loser.setName("落选C");
        loser.setNumber("3");
        loser.setSeedRank(3L);
        loser.setFinalRank(3L);
        loser.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
        competitorMapper.insert(loser);

        // 源结算 → 两来源组就绪 → 整单装配:2 主晋级 + 1 复活
        TStage settled = new TStage();
        settled.setId(stageA.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        int created = rosterService.applyRoster(stageB.getId(), null);
        assertEquals(3, created);
        List<TCompetitor> rows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageB.getId()));
        assertEquals(3, rows.size());
        long advanceTags = rows.stream().filter(r -> RosterConstants.ENTRY_ADVANCE.equals(r.getEntryTag())).count();
        long reviveTags = rows.stream().filter(r -> RosterConstants.ENTRY_REVIVE.equals(r.getEntryTag())).count();
        assertEquals(2, advanceTags);
        assertEquals(1, reviveTags);
        assertTrue(rosterService.listByTarget(stageB.getId()).stream()
            .allMatch(p -> RosterConstants.ROSTER_CONFIRMED.equals(p.getState())));
    }

    @Test
    void endToEndRosterFlowAcrossStages() {
        TTournament tournament = new TTournament();
        tournament.setName("端到端名单流转赛事");
        tournamentMapper.insert(tournament);

        final String KNOCKOUT_FINAL =
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"FINAL\",\"teamsCount\":2,"
                + "\"advanceCount\":1,\"format\":\"BO1\",\"publishMode\":\"AUTO\",\"pairingMode\":\"SEQUENTIAL\"},"
                + "\"scoring\":{\"matchMode\":\"STANDARD\"}}";
        final String AUDITION_REVIVE =
            "{\"mode\":\"AUDITION\",\"advanceCount\":1,\"format\":\"BO1\",\"maxScore\":10,\"circles\":1}";

        TStageVo pre = createStage(tournament.getId(), "预选", "KNOCKOUT", 2L, 1L, null, KNOCKOUT_FINAL);
        TStageVo revive = createStage(tournament.getId(), "复活海选", "AUDITION", 0L, 1L, pre.getId(), AUDITION_REVIVE);
        TStageVo finals = createStage(tournament.getId(), "决赛", "KNOCKOUT", 2L, 1L, revive.getId(), KNOCKOUT_FINAL);

        // 复活赛不接收主晋级:先在唯一名单行追加 ELIMINATED 来源组,再删默认 ADVANCE 组
        TStageRosterVo reviveRoster = rosterService.listByTarget(revive.getId()).get(0);
        TStageRosterBo reviveGroups = new TStageRosterBo();
        reviveGroups.setSourceStageId(pre.getId());
        reviveGroups.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        reviveGroups.setFillMode(RosterConstants.FILL_AUTO);
        reviveGroups.setQuota(0);
        reviveGroups.setPriority(1);
        rosterService.addGroups(revive.getId(), reviveGroups);
        rosterService.removeGroup(reviveRoster.getId(), 0);

        // 预选:两名待赛选手 → 开赛 → 判一场 → 完成(产出 1 晋级 + 1 落选)
        insertPendingCompetitor(tournament.getId(), pre.getId(), "正赛A", "1");
        insertPendingCompetitor(tournament.getId(), pre.getId(), "正赛B", "2");
        lifecycleService.startStage(pre.getId());
        finishKnockoutStage(pre.getId());
        lifecycleService.completeStage(pre.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(pre.getId()).getStatus());
        assertEquals(1, countOutcome(pre.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(1, countOutcome(pre.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        // 决赛先接收"预选晋级直入"来源(跨级来源),之后还会接收复活赛胜者
        TStageRosterBo directRoster = new TStageRosterBo();
        directRoster.setSourceStageId(pre.getId());
        directRoster.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        directRoster.setFillMode(RosterConstants.FILL_AUTO);
        directRoster.setQuota(0);
        directRoster.setPriority(2);
        rosterService.addGroups(finals.getId(), directRoster);

        // 复活海选:落选者经复活名单进入 → 打分 → 完成
        assertEquals(1, rosterService.applyRoster(revive.getId(), null), "复活名单应带入 1 名落选者");
        List<TCompetitor> reviveRows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, revive.getId()));
        assertEquals(1, reviveRows.size());
        assertEquals(RosterConstants.ENTRY_REVIVE, reviveRows.get(0).getEntryTag());
        lifecycleService.startStage(revive.getId());
        scoreAllAuditionParticipants(revive.getId());
        assertEquals(StageConstants.STAGE_SETTLED, lifecycleService.completeStage(revive.getId()));
        assertEquals(1, countOutcome(revive.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        // 决赛:默认名单(复活赛胜者) + 跨级直入来源(预选胜者)整单汇入
        assertEquals(2, rosterService.applyRoster(finals.getId(), null), "决赛应汇入 2 名选手");
        List<TCompetitor> finalRows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, finals.getId()));
        assertEquals(2, finalRows.size());
        assertTrue(finalRows.stream().anyMatch(r -> pre.getId().equals(r.getSourceStageId())));
        assertTrue(finalRows.stream().anyMatch(r -> revive.getId().equals(r.getSourceStageId())));
        assertTrue(rosterService.listByTarget(finals.getId()).stream()
            .allMatch(p -> RosterConstants.ROSTER_CONFIRMED.equals(p.getState())));

        // 决赛开赛 → 判一场 → 完成,全流程闭环
        lifecycleService.startStage(finals.getId());
        finishKnockoutStage(finals.getId());
        lifecycleService.completeStage(finals.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(finals.getId()).getStatus());
        assertEquals(1, countOutcome(finals.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
    }

    /** 默认路径(calculateAdvancement 委托 roster apply)与种子覆盖/容量硬校验/覆盖 REMOVE 语义 */
    @Test
    void defaultApplyHonorsSeedOverridesAndCapacitySqueeze() {
        TTournament tournament = new TTournament();
        tournament.setName("默认路径名单赛事");
        tournamentMapper.insert(tournament);

        TStageVo stage1 = stageService.insertByBo(baseStage(tournament.getId(), "海选", "AUDITION", 0L, 3L, null));
        TStageVo stage2 = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 2L, 1L, stage1.getId()));
        TStage stage2Rule = new TStage();
        stage2Rule.setId(stage2.getId());
        stage2Rule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":2,\"advanceCount\":1,\"format\":\"BO1\"}}");
        stageMapper.updateById(stage2Rule);

        // 源赛段 3 名晋级者(名额 16 强只收 2)
        TCompetitor a = insertCompetitorWithReturn(tournament.getId(), stage1.getId(), "晋级A", "1", 1L);
        TCompetitor b = insertCompetitorWithReturn(tournament.getId(), stage1.getId(), "晋级B", "2", 2L);
        TCompetitor c = insertCompetitorWithReturn(tournament.getId(), stage1.getId(), "晋级C", "3", 3L);
        TStage settled = new TStage();
        settled.setId(stage1.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        // 容量为硬约束:3 名候选超出计划 2 人时直接报错,不再静默改源淘汰
        assertThrows(ServiceException.class,
            () -> rosterService.applyRoster(stage2.getId(), null));
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(c.getId()).getOutcomeStatus());

        // 显式 REMOVE 覆盖 C 后装配成功:预排 B 进 1 号位、A 进 2 号位
        TStageRosterOverrideBo removeC = new TStageRosterOverrideBo();
        removeC.setOp(RosterConstants.OVERRIDE_REMOVE);
        removeC.setSourceCompetitorId(c.getId());
        Long stageId = rosterService.listByTarget(stage2.getId()).get(0).getId();
        rosterService.addOverride(stageId, removeC);
        TStageRosterOverrideBo seedB = new TStageRosterOverrideBo();
        seedB.setOp(RosterConstants.OVERRIDE_SEED);
        seedB.setSourceCompetitorId(b.getId());
        seedB.setSeedRank(1L);
        rosterService.addOverride(stageId, seedB);
        TStageRosterOverrideBo seedA = new TStageRosterOverrideBo();
        seedA.setOp(RosterConstants.OVERRIDE_SEED);
        seedA.setSourceCompetitorId(a.getId());
        seedA.setSeedRank(2L);
        rosterService.addOverride(stageId, seedA);
        int created = rosterService.applyRoster(stage2.getId(), null);
        assertEquals(2, created);
        Map<Long, Long> seedOf = new java.util.HashMap<>();
        competitorMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, stage2.getId()))
            .forEach(r -> seedOf.put(r.getSourceCompetitorId(), r.getSeedRank()));
        assertEquals(1L, seedOf.get(b.getId()));
        assertEquals(2L, seedOf.get(a.getId()));
        // REMOVE 只影响名单,源赛段 C 保持晋级结果(不再有写源副作用)
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(c.getId()).getOutcomeStatus());
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(a.getId()).getOutcomeStatus());
        assertEquals(RosterConstants.ROSTER_CONFIRMED,
            rosterService.listByTarget(stage2.getId()).get(0).getState());

        // 已确认后再调返回 0(幂等)
        assertEquals(0, lifecycleService.calculateAdvancement(stage1.getId()));

    }

    /** 下一场/对战树预排改从名单来源组取数:复活候选(ELIMINATED 组)也应进入预排 */
    @Test
    void prebracketUsesRosterSourceGroups() {
        TTournament tournament = new TTournament();
        tournament.setName("预排名单赛事");
        tournamentMapper.insert(tournament);

        TStageVo stageA = stageService.insertByBo(baseStage(tournament.getId(), "海选", "AUDITION", 0L, 3L, null));
        TStageVo stageB = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 3L, 1L, stageA.getId()));
        TStage stageBRule = new TStage();
        stageBRule.setId(stageB.getId());
        stageBRule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":4,\"advanceCount\":1,\"format\":\"BO1\"}}");
        stageMapper.updateById(stageBRule);

        insertCompetitor(tournament.getId(), stageA.getId(), "晋级A", "1", 1L);
        insertCompetitor(tournament.getId(), stageA.getId(), "晋级B", "2", 2L);
        TCompetitor loser = new TCompetitor();
        loser.setTournamentId(tournament.getId());
        loser.setStageId(stageA.getId());
        loser.setType(0L);
        loser.setName("复活候选C");
        loser.setNumber("3");
        loser.setSeedRank(3L);
        loser.setFinalRank(3L);
        loser.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
        competitorMapper.insert(loser);

        // 16 强名单 = 默认晋级组 + 复活来源组(海选落选)
        TStageRosterBo revive = new TStageRosterBo();
        revive.setSourceStageId(stageA.getId());
        revive.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        revive.setFillMode(RosterConstants.FILL_AUTO);
        revive.setQuota(0);
        revive.setPriority(2);
        rosterService.addGroups(stageB.getId(), revive);

        TStage settled = new TStage();
        settled.setId(stageA.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        // 预排必须同时含主晋级与复活候选(旧实现只会返回上一赛段 ADVANCE 2 人)
        com.dance.street.game.domain.vo.PreBracketVo pb = stageService.getPreBracket(stageB.getId());
        assertEquals("PREVIEW", pb.getStatus());
        assertNotNull(pb.getSeededCompetitors());
        assertEquals(3, pb.getSeededCompetitors().size());
        assertTrue(pb.getSeededCompetitors().stream().anyMatch(s -> "复活候选C".equals(s.getName())));

        // 出口视角:listBySource 按 groups 扫描,同一名单行的两组成员都应被识别
        List<TStageRosterVo> bySource = rosterService.listBySource(stageA.getId());
        assertEquals(1, bySource.size());
        assertEquals(2, bySource.get(0).getGroups().size());
        assertTrue(bySource.get(0).getGroups().stream()
            .allMatch(g -> stageA.getId().equals(g.getSourceStageId())));

        // 编辑来源组规则(出口自定义配置)
        com.dance.street.game.domain.bo.TStageRosterGroupBo edited = new com.dance.street.game.domain.bo.TStageRosterGroupBo();
        edited.setSourceStageId(stageA.getId());
        edited.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        edited.setFillMode(RosterConstants.FILL_AUTO);
        edited.setQuota(0);
        rosterService.updateGroup(bySource.get(0).getId(), 1, edited);
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            rosterService.listByTarget(stageB.getId()).get(0).getGroups().get(1).getResultFilter());
    }

    /** 规则+覆盖+快照:REMOVE/SEED/ADD_GUEST 只影响名单,不写源;覆盖在 reset 后保留 */
    @Test
    void overrideDeltaShapesAssembledRoster() {
        TTournament tournament = new TTournament();
        tournament.setName("覆盖层名单赛事");
        tournamentMapper.insert(tournament);

        TStageVo audition = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 5L, null));
        TStageVo round16 = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 4L, 1L, audition.getId()));
        TStage rule = new TStage();
        rule.setId(round16.getId());
        rule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":4,\"advanceCount\":1,\"format\":\"BO1\"}}");
        stageMapper.updateById(rule);

        TCompetitor a = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级A", "1", 1L);
        TCompetitor b = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级B", "2", 2L);
        TCompetitor c = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级C", "3", 3L);
        TCompetitor d = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级D", "4", 4L);
        TCompetitor e = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级E", "5", 5L);
        // 额外落选行(仅用于 ADD_SOURCE 补位场景)
        TCompetitor f = new TCompetitor();
        f.setTournamentId(tournament.getId());
        f.setStageId(audition.getId());
        f.setType(0L);
        f.setName("落选F");
        f.setNumber("6");
        f.setSeedRank(6L);
        f.setFinalRank(6L);
        f.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
        competitorMapper.insert(f);

        TStage settled = new TStage();
        settled.setId(audition.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);
        // 故意不调 markReadyBySource:就绪度必须由源状态推导

        Long stageId = rosterService.listByTarget(round16.getId()).get(0).getId();
        // 5 名候选超计划 4:直接报错(不自动挤位)
        assertThrows(ServiceException.class,
            () -> rosterService.applyRoster(round16.getId(), null));
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(e.getId()).getOutcomeStatus());

        // REMOVE E 后装配成功,顺序 A/B/C/D 落种子 1..4
        TStageRosterOverrideBo removeE = new TStageRosterOverrideBo();
        removeE.setOp(RosterConstants.OVERRIDE_REMOVE);
        removeE.setSourceCompetitorId(e.getId());
        rosterService.addOverride(stageId, removeE);
        assertEquals(4, rosterService.applyRoster(round16.getId(), null));
        Map<Long, Long> seedOf = new java.util.HashMap<>();
        competitorMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, round16.getId()))
            .forEach(r -> seedOf.put(r.getSourceCompetitorId(), r.getSeedRank()));
        assertEquals(1L, seedOf.get(a.getId()));
        assertEquals(2L, seedOf.get(b.getId()));
        assertEquals(3L, seedOf.get(c.getId()));
        assertEquals(4L, seedOf.get(d.getId()));
        // 覆盖不写源:落选 F 仍为 ELIMINATED、E 仍 ADVANCE
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(e.getId()).getOutcomeStatus());

        // reset 后快照行删除,REMOVE 覆盖保留;预览与装配口径一致
        lifecycleService.resetStageToDraft(round16.getId());
        assertEquals(0, competitorMapper.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round16.getId())));
        assertEquals(1, rosterService.listOverrides(stageId).size());
        com.dance.street.game.domain.vo.RosterPreviewVo preview =
            rosterService.previewAssembled(stageId);
        assertEquals(Boolean.TRUE, preview.getReady());
        assertEquals(Boolean.FALSE, preview.getApplied());
        assertEquals(4, preview.getItems().size());
        assertEquals(4, preview.getCapacity().intValue());

        // SEED C→1 + SEED D→2:强制占位后 A/B 顺延
        TStageRosterOverrideBo seedC = new TStageRosterOverrideBo();
        seedC.setOp(RosterConstants.OVERRIDE_SEED);
        seedC.setSourceCompetitorId(c.getId());
        seedC.setSeedRank(1L);
        rosterService.addOverride(stageId, seedC);
        TStageRosterOverrideBo seedD = new TStageRosterOverrideBo();
        seedD.setOp(RosterConstants.OVERRIDE_SEED);
        seedD.setSourceCompetitorId(d.getId());
        seedD.setSeedRank(2L);
        rosterService.addOverride(stageId, seedD);
        assertEquals(4, rosterService.applyRoster(round16.getId(), null));
        seedOf.clear();
        competitorMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, round16.getId()))
            .forEach(r -> seedOf.put(r.getSourceCompetitorId(), r.getSeedRank()));
        assertEquals(1L, seedOf.get(c.getId()));
        assertEquals(2L, seedOf.get(d.getId()));
        assertEquals(3L, seedOf.get(a.getId()));
        assertEquals(4L, seedOf.get(b.getId()));

        // 移除 D(快照前,保留 REMOVE E/SEED C)并加外卡 X:4 人内 X 自动队尾,G 号
        lifecycleService.resetStageToDraft(round16.getId());
        TStageRosterOverrideBo removeD = new TStageRosterOverrideBo();
        removeD.setOp(RosterConstants.OVERRIDE_REMOVE);
        removeD.setSourceCompetitorId(d.getId());
        rosterService.addOverride(stageId, removeD);
        TStageRosterOverrideBo addX = new TStageRosterOverrideBo();
        addX.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        addX.setGuestName("外卡X");
        addX.setGuestType(0L);
        rosterService.addOverride(stageId, addX);
        assertEquals(4, rosterService.applyRoster(round16.getId(), null));
        List<TCompetitor> afterGuest = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round16.getId())
                .orderByAsc(TCompetitor::getSeedRank));
        assertEquals(4, afterGuest.size());
        TCompetitor xRow = afterGuest.stream()
            .filter(r -> "外卡X".equals(r.getName()))
            .findFirst().orElse(null);
        assertNotNull(xRow);
        assertEquals(RosterConstants.ENTRY_GUEST, xRow.getEntryTag());
        assertEquals("GUEST", xRow.getRemark());
        assertEquals(4L, xRow.getSeedRank().longValue());
        assertNotNull(xRow.getNumber());
        assertTrue(xRow.getNumber().startsWith("G"));

        // ADD_SOURCE 补位:落选 F 虽不匹配默认晋级组,可显式拉入(超容时预览给出警告)
        lifecycleService.resetStageToDraft(round16.getId());
        TStageRosterOverrideBo addF = new TStageRosterOverrideBo();
        addF.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        addF.setSourceCompetitorId(f.getId());
        rosterService.addOverride(stageId, addF);
        preview = rosterService.previewAssembled(stageId);
        assertTrue(preview.getItems().stream()
            .anyMatch(i -> "落选F".equals(i.getName())));
        assertFalse(preview.getWarnings().isEmpty());
        assertEquals(RosterConstants.ENTRY_REVIVE, preview.getItems().stream()
            .filter(i -> "落选F".equals(i.getName()))
            .findFirst().get().getEntryTag());
        // 仍超 1 人:装配会被硬容量拒绝
        assertThrows(ServiceException.class,
            () -> rosterService.applyRoster(round16.getId(), null));

        // 同一名单可以加多个不同外卡(按姓名去重,不再"只能有一张外卡")
        TStageRosterOverrideBo addX2 = new TStageRosterOverrideBo();
        addX2.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        addX2.setGuestName("外卡Y");
        addX2.setGuestType(0L);
        rosterService.addOverride(stageId, addX2);
        preview = rosterService.previewAssembled(stageId);
        assertEquals(2, preview.getItems().stream()
            .filter(i -> "GUEST".equals(i.getRefType())).count());
        // 同名外卡仍会被去重拦下
        TStageRosterOverrideBo dup = new TStageRosterOverrideBo();
        dup.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        dup.setGuestName("外卡Y");
        dup.setGuestType(0L);
        assertThrows(ServiceException.class, () -> rosterService.addOverride(stageId, dup));
    }

    /** S1 双写:名单即赛段属性:roster_config_json/applied/skipped 落于 t_stage */
    @Test
    void rosterColumnsLiveOnStage() {
        TTournament tournament = new TTournament();
        tournament.setName("S1双写赛事");
        tournamentMapper.insert(tournament);

        TStageVo stage1 = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 3L, null));
        TStageVo stage2 = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 2L, 1L, stage1.getId()));

        // 建段默认组已双写:stage.roster_config_json 含 groups
        TStage stageRow = stageMapper.selectById(stage2.getId());
        assertNotNull(stageRow.getRosterConfigJson());
        assertTrue(stageRow.getRosterConfigJson().contains("\"groups\""));
        assertEquals(0L, stageRow.getRosterApplied().longValue());
        assertEquals(0L, stageRow.getRosterSkipped().longValue());

        // 追加来源组(复活)后 config 双写为 2 组
        TStageRosterBo revive = new TStageRosterBo();
        revive.setSourceStageId(stage1.getId());
        revive.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        revive.setFillMode(RosterConstants.FILL_AUTO);
        revive.setPriority(2);
        rosterService.addGroups(stage2.getId(), revive);
        TStageRosterVo vo = rosterService.listByTarget(stage2.getId()).get(0);
        assertEquals(2, vo.getGroups().size());
        stageRow = stageMapper.selectById(stage2.getId());
        assertNotNull(stageRow.getRosterConfigJson());
        assertTrue(stageRow.getRosterConfigJson().contains("\"groups\""));

        // apply 成功后 roster_applied=1;reset 后回 0
        insertCompetitorWithReturn(tournament.getId(), stage1.getId(), "晋级A", "1", 1L);
        insertCompetitorWithReturn(tournament.getId(), stage1.getId(), "晋级B", "2", 2L);
        TStage settled = new TStage();
        settled.setId(stage1.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);
        assertEquals(2, rosterService.applyRoster(stage2.getId(), null));
        assertEquals(1L, stageMapper.selectById(stage2.getId()).getRosterApplied().longValue());
        // S2b:快照行 from_roster=1
        assertTrue(competitorMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, stage2.getId()))
            .stream().allMatch(r -> Long.valueOf(1L).equals(r.getFromRoster())));
        lifecycleService.resetStageToDraft(stage2.getId());
        assertEquals(0L, stageMapper.selectById(stage2.getId()).getRosterApplied().longValue());

        // 显式跳过 → roster_skipped=1;reset → 0
        rosterService.markSkipped(vo.getId());
        assertEquals(1L, stageMapper.selectById(stage2.getId()).getRosterSkipped().longValue());
        lifecycleService.resetStageToDraft(stage2.getId());
        assertEquals(0L, stageMapper.selectById(stage2.getId()).getRosterSkipped().longValue());
    }

    /** 未配置出口时:上一赛段晋级者默认进入下一赛段(删掉最后一条引用前驱的组会自动补回) */
    @Test
    void prevChainDefaultRestoredWhenNoExitConfigured() {
        TTournament tournament = new TTournament();
        tournament.setName("默认衔接兜底赛事");
        tournamentMapper.insert(tournament);

        TStageVo s0 = stageService.insertByBo(
            baseStage(tournament.getId(), "预选", "KNOCKOUT", 2L, 1L, null));
        TStageVo s1 = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 16L, s0.getId()));
        TStageVo s2 = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 16L, 8L, s1.getId()));

        // 建段时自动生成默认衔接:直接前驱(s1)的晋级者
        List<TStageRosterGroupBo> groups = rosterService.listByTarget(s2.getId()).get(0).getGroups();
        assertEquals(1, groups.size());
        assertEquals(s1.getId(), groups.get(0).getSourceStageId());

        // 追加一条跨级来源(引用更早的 s0,不指向直接前驱),再删掉默认的 s1 组
        TStageRosterBo extra = new TStageRosterBo();
        extra.setSourceStageId(s0.getId());
        extra.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        extra.setFillMode(RosterConstants.FILL_AUTO);
        extra.setQuota(0);
        rosterService.addGroups(s2.getId(), extra);
        List<TStageRosterGroupBo> before = rosterService.listByTarget(s2.getId()).get(0).getGroups();
        assertEquals(2, before.size());
        int defaultIdx = -1;
        for (int i = 0; i < before.size(); i++) {
            if (s1.getId().equals(before.get(i).getSourceStageId())) {
                defaultIdx = i;
                break;
            }
        }
        assertTrue(defaultIdx >= 0);
        rosterService.removeGroup(s2.getId(), defaultIdx);

        // 删除后已无任何组引用直接前驱 → 自动补回"上一赛段·晋级"默认衔接
        List<TStageRosterGroupBo> after = rosterService.listByTarget(s2.getId()).get(0).getGroups();
        assertTrue(after.stream().anyMatch(g -> s1.getId().equals(g.getSourceStageId())),
            "未配置出口时应自动补回直接前驱的默认衔接");
    }

    /** 中间插段(A→Z→B)后:B 的 prev 变为 Z,名单默认来源应从 A 迁移到 Z */
    @Test
    void chainInsertMigratesDownstreamDefaultSource() {
        TTournament tournament = new TTournament();
        tournament.setName("插段名单对账赛事");
        tournamentMapper.insert(tournament);

        TStageVo audition = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 8L, null));
        TStageVo round16 = stageService.insertByBo(
            baseStage(tournament.getId(), "16强", "KNOCKOUT", 8L, 1L, audition.getId()));
        assertEquals(audition.getId(), rosterService.listByTarget(round16.getId()).get(0).getGroups().get(0).getSourceStageId());

        // 在海选与 16 强之间插入"复活赛"(prev=海选,next=16强)
        TStageBo inserted = baseStage(tournament.getId(), "复活赛", "KNOCKOUT", 8L, 4L, audition.getId());
        inserted.setNextStageId(round16.getId());
        stageService.insertByBo(inserted);

        TStage round16After = stageMapper.selectById(round16.getId());
        assertNotNull(round16After.getPrevStageId());
        assertEquals("复活赛", stageMapper.selectById(round16After.getPrevStageId()).getName());
        List<TStageRosterGroupBo> groups = rosterService.listByTarget(round16.getId()).get(0).getGroups();
        assertTrue(groups.stream().anyMatch(g -> stageMapper.selectById(round16After.getPrevStageId()).getId()
            .equals(g.getSourceStageId())));
        assertTrue(groups.stream().noneMatch(g -> audition.getId().equals(g.getSourceStageId())));
    }

    /** 手工名单:C 拖到首位 → 移出 A → 补落选者 F → 加外卡 G,顺序即出场次序 */
    @Test
    void manualRosterEditingIsFreeForm() {
        TTournament tournament = new TTournament();
        tournament.setName("手工名单赛事");
        tournamentMapper.insert(tournament);

        TStageVo audition = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 5L, null));
        TStageVo round8 = stageService.insertByBo(
            baseStage(tournament.getId(), "8强", "KNOCKOUT", 6L, 1L, audition.getId()));
        TStage rule = new TStage();
        rule.setId(round8.getId());
        rule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":6,\"advanceCount\":1,\"format\":\"BO1\"}}");
        stageMapper.updateById(rule);

        TCompetitor a = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "A", "1", 1L);
        TCompetitor b = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "B", "2", 2L);
        TCompetitor c = insertCompetitorWithReturn(tournament.getId(), audition.getId(), "C", "3", 3L);
        // 落选者:规则不会带入,只能手工补
        TCompetitor f = new TCompetitor();
        f.setTournamentId(tournament.getId());
        f.setStageId(audition.getId());
        f.setType(0L);
        f.setName("落选F");
        f.setNumber("9");
        f.setFinalRank(9L);
        f.setOutcomeStatus(OutcomeStatusEnum.ELIMINATED.getCode());
        competitorMapper.insert(f);

        TStage settled = new TStage();
        settled.setId(audition.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        Long stageId = rosterService.listByTarget(round8.getId()).get(0).getId();
        assertEquals(List.of("A", "B", "C"), assembledNames(round8.getId()));

        // 1) 拖动排序:C 排到第一位
        rosterService.reorderRoster(stageId, List.of(orderItem(c.getId()), orderItem(a.getId()), orderItem(b.getId())));
        assertEquals(List.of("C", "A", "B"), assembledNames(round8.getId()));

        // 2) 移出 A,并保持 C 在前
        TStageRosterOverrideBo removeA = new TStageRosterOverrideBo();
        removeA.setOp(RosterConstants.OVERRIDE_REMOVE);
        removeA.setSourceCompetitorId(a.getId());
        rosterService.addOverride(stageId, removeA);
        rosterService.reorderRoster(stageId, List.of(orderItem(c.getId()), orderItem(b.getId())));

        // 3) 从同一赛段手工补落选者 F:不受来源组规则限制
        TStageRosterOverrideBo addF = new TStageRosterOverrideBo();
        addF.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        addF.setSourceCompetitorId(f.getId());
        rosterService.addOverride(stageId, addF);
        rosterService.reorderRoster(stageId,
            List.of(orderItem(c.getId()), orderItem(b.getId()), orderItem(f.getId())));

        // 4) 外卡(无来源)
        TStageRosterOverrideBo guest = new TStageRosterOverrideBo();
        guest.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        guest.setGuestName("特邀G");
        guest.setGuestType(0L);
        rosterService.addOverride(stageId, guest);
        rosterService.reorderRoster(stageId,
            List.of(orderItem(c.getId()), orderItem(b.getId()), orderItem(f.getId())));

        assertEquals(List.of("C", "B", "落选F", "特邀G"), assembledNames(round8.getId()));

        // 5) 确认名单:列表位置即出场次序
        assertEquals(4, rosterService.applyRoster(round8.getId(), null));
        Map<String, Long> seedOf = new java.util.HashMap<>();
        competitorMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, round8.getId()))
            .forEach(r -> seedOf.put(r.getName(), r.getSeedRank()));
        assertEquals(1L, seedOf.get("C"));
        assertEquals(2L, seedOf.get("B"));
        assertEquals(3L, seedOf.get("落选F"));
        assertEquals(4L, seedOf.get("特邀G"));
    }

    private List<String> assembledNames(Long stageId) {
        return rosterService.previewAssembled(stageId).getItems().stream()
            .map(RosterPreviewItemVo::getName)
            .toList();
    }

    private TStageRosterOrderBo.Item orderItem(Long sourceCompetitorId) {
        TStageRosterOrderBo.Item item = new TStageRosterOrderBo.Item();
        item.setSourceCompetitorId(sourceCompetitorId);
        return item;
    }

    /** 手动补参赛方(带返回实体,便于断言种子映射) */
    private TCompetitor insertCompetitorWithReturn(Long tournamentId, Long stageId, String name,
                                                   String number, Long finalRank) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tournamentId);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank(finalRank);
        c.setFinalRank(finalRank);
        c.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        competitorMapper.insert(c);
        return c;
    }

    private TStageVo createStage(Long tournamentId, String name, String mode,
                                 Long start, Long end, Long prevStageId, String ruleConfig) {
        TStageBo bo = baseStage(tournamentId, name, mode, start, end, prevStageId);
        bo.setRuleConfig(ruleConfig);
        return stageService.insertByBo(bo);
    }

    private void insertPendingCompetitor(Long tournamentId, Long stageId, String name, String number) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tournamentId);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    /** 淘汰赛单场(2 人)直接判定胜负并结算 */
    private void finishKnockoutStage(Long stageId) {
        List<TMatch> matches = matchMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId));
        assertEquals(1, matches.size());
        // 淘汰赛逐场放行:先开始场次,裁判才可判罚
        matchResultService.startMatch(matches.get(0).getId());
        List<TMatchParticipant> parts = participantMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matches.get(0).getId())
                .isNotNull(TMatchParticipant::getCompetitorId)
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertEquals(2, parts.size());
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matches.get(0).getId());
        bo.setRefereeId(null);
        bo.setOutcomes(Map.of(
            parts.get(0).getCompetitorId(), "WIN",
            parts.get(1).getCompetitorId(), "LOSS"));
        matchResultService.submitResult(bo);
    }

    /** 海选逐选手打分:给本赛段全部参赛者每人一个分数 */
    private void scoreAllAuditionParticipants(Long stageId) {
        List<TMatch> matches = matchMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId));
        assertEquals(1, matches.size());
        List<TMatchParticipant> parts = participantMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matches.get(0).getId())
                .isNotNull(TMatchParticipant::getCompetitorId));
        assertEquals(1, parts.size());
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matches.get(0).getId());
        bo.setRefereeId(null);
        ScoreEntryBo score = new ScoreEntryBo();
        score.setCompetitorId(parts.get(0).getCompetitorId());
        score.setScore(new BigDecimal("8.0"));
        bo.setScores(List.of(score));
        matchResultService.submitResult(bo);
    }

    /**
     * 回归:名单已装配后「保存赛段/开始赛段回写」(链路未变)不应报错。
     * 旧实现按"传进来的旧前驱"判断,导致普通保存被误判为改链,抛
     * "赛段[xx]名单已装配,请先重置该赛段后再调整赛段链路"。
     */
    @Test
    void savingStageWithLockedRosterAndUnchangedLinksDoesNotThrow() {
        TTournament tournament = new TTournament();
        tournament.setName("保存赛段回归赛事");
        tournamentMapper.insert(tournament);

        TStageVo audition = stageService.insertByBo(
            baseStage(tournament.getId(), "海选", "AUDITION", 0L, 2L, null));
        TStageVo fin = stageService.insertByBo(
            baseStage(tournament.getId(), "决赛", "KNOCKOUT", 2L, 1L, audition.getId()));

        insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级A", "1", 1L);
        insertCompetitorWithReturn(tournament.getId(), audition.getId(), "晋级B", "2", 2L);
        TStage settled = new TStage();
        settled.setId(audition.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        assertEquals(2, rosterService.applyRoster(fin.getId(), null));
        assertEquals(RosterConstants.ROSTER_CONFIRMED,
            rosterService.listByTarget(fin.getId()).get(0).getState());

        // 前端「开始赛段/完成赛段」后会回写整段(指针与库内一致)→ 不应因名单已装而被拦
        TStageBo sameLink = baseStage(tournament.getId(), "决赛", "KNOCKOUT", 2L, 1L, audition.getId());
        sameLink.setId(fin.getId());
        sameLink.setStatus(StageConstants.STAGE_GAMING);
        assertDoesNotThrow(() -> stageService.updateByBo(sameLink));

        // 真的改链(换一个直接前驱)时才要求先重置该赛段
        TStageVo anotherAudition = stageService.insertByBo(
            baseStage(tournament.getId(), "另一入口", "AUDITION", 0L, 2L, null));
        TStageBo relink = baseStage(tournament.getId(), "决赛", "KNOCKOUT", 2L, 1L, anotherAudition.getId());
        relink.setId(fin.getId());
        assertThrows(ServiceException.class, () -> stageService.updateByBo(relink));
        // 拦截必须整单回滚:链路保持原样,不能出现「报错了但链已改」的中间状态
        assertEquals(audition.getId(), stageMapper.selectById(fin.getId()).getPrevStageId());
    }

    /** 入口赛段(仅签到 STREAM 组)也接受显式外卡:覆盖是人工决定,不依赖来源组 */
    @Test
    void guestOverrideMaterializesOnEntryStage() {
        TTournament tournament = new TTournament();
        tournament.setName("入口赛段外卡赛事");
        tournamentMapper.insert(tournament);

        TStageVo stage = stageService.insertByBo(
            baseStage(tournament.getId(), "8强", "KNOCKOUT", 2L, 1L, null));
        TStage rule = new TStage();
        rule.setId(stage.getId());
        rule.setRuleConfig(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"template\":\"ROUND_8\",\"teamsCount\":2,"
                + "\"advanceCount\":1,\"format\":\"BO1\"}}");
        stageMapper.updateById(rule);

        Long stageId = rosterService.listByTarget(stage.getId()).get(0).getId();
        TStageRosterOverrideBo guest = new TStageRosterOverrideBo();
        guest.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        guest.setGuestName("外卡甲");
        guest.setGuestType(0L);
        rosterService.addOverride(stageId, guest);

        assertEquals(1, rosterService.applyRoster(stage.getId(), null));
        List<TCompetitor> rows = competitorMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId()));
        assertEquals(1, rows.size());
        assertEquals("外卡甲", rows.get(0).getName());
        assertEquals(RosterConstants.ENTRY_GUEST, rows.get(0).getEntryTag());
        assertEquals(RosterConstants.ROSTER_CONFIRMED,
            rosterService.listByTarget(stage.getId()).get(0).getState());
    }

    private TStageBo baseStage(Long tournamentId, String name, String mode,
                               Long start, Long end, Long prevStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setPrevStageId(prevStageId);
        bo.setIsInitialized(0L);
        return bo;
    }

    private void insertCompetitor(Long tournamentId, Long stageId, String name,
                                  String number, Long finalRank) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tournamentId);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank(finalRank);
        c.setFinalRank(finalRank);
        c.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        competitorMapper.insert(c);
    }
}
