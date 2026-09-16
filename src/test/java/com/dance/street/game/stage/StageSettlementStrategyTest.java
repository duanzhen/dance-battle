package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.settle.ArenaStageSettler;
import com.dance.street.game.service.impl.settle.AuditionStageSettler;
import com.dance.street.game.service.impl.settle.GroupStageSettler;
import com.dance.street.game.service.impl.settle.RankStageSettler;
import com.dance.street.game.service.impl.settle.ScoredStageSettler;
import com.dance.street.game.service.impl.settle.StageSettler;
import com.dance.street.game.service.impl.settle.StageSettlerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段结算策略:装配完整性与「完成赛段」的统一失败语义。
 *
 * <p>两件事此前没有测试兜底:</p>
 * <ul>
 *   <li>新增赛制如果忘了写结算策略,要到现场点「完成赛段」才炸——这里把
 *       "每个赛制都有策略"变成断言;</li>
 *   <li>"赛段还不能结束"过去在淘汰赛/小组赛抛异常、在海选/排名赛返回 GAMING,
 *       调用方必须按赛制分别处理。统一为 {@code completed=false + message} 后
 *       在这里锁住口径。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageSettlementStrategyTest {

    private static final String DB_PATH = "target/stage-settlement-strategy.db";

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
    private StageSettlerRegistry settlerRegistry;
    @Autowired
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private TMatchMapper matchMapper;
    @Autowired
    private TMatchParticipantMapper participantMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;

    /**
     * 注册表覆盖性:每个 {@link StageModeEnum} 都能解析出结算策略。
     * 新增赛制却忘了加策略时,这条会先红,而不是线上点「完成赛段」才报错。
     */
    @Test
    void everyStageModeHasSettler() {
        List<String> uncovered = settlerRegistry.uncoveredModes();
        assertTrue(uncovered.isEmpty(), "以下赛制没有结算策略: " + uncovered);
        for (StageModeEnum mode : StageModeEnum.values()) {
            StageSettler settler = settlerRegistry.of(mode.getCode());
            assertNotNull(settler, "赛制 " + mode.getCode() + " 无策略");
        }
    }

    /** 分派正确性:每种赛制落到预期策略(淘汰赛与自由对抗共用计分策略)。 */
    @Test
    void registryDispatchesToExpectedSettler() {
        assertInstanceOf(AuditionStageSettler.class,
            settlerRegistry.of(StageModeEnum.AUDITION.getCode()));
        assertInstanceOf(RankStageSettler.class,
            settlerRegistry.of(StageModeEnum.RANK.getCode()));
        assertInstanceOf(GroupStageSettler.class,
            settlerRegistry.of(StageModeEnum.GROUP.getCode()));
        assertInstanceOf(ArenaStageSettler.class,
            settlerRegistry.of(StageModeEnum.ARENA.getCode()));
        assertInstanceOf(ScoredStageSettler.class,
            settlerRegistry.of(StageModeEnum.KNOCKOUT.getCode()));
        assertInstanceOf(ScoredStageSettler.class,
            settlerRegistry.of(StageModeEnum.FREE_MATCH.getCode()));
    }

    /**
     * 场次未打完时「完成赛段」是正常结果而非异常:
     * 返回 completed=false + 原因,赛段保持 GAMING;打完后再点才置 SETTLED。
     */
    @Test
    void unfinishedStageReportsPendingInsteadOfThrowing() {
        Long tid = newTournament("完成语义");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);

        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        // 一场未打就点「完成赛段」:过去这里抛 ServiceException
        StageCompleteVo early = lifecycleService.completeStage(stage.getId());
        assertFalse(early.getCompleted(), "场次未打完不应算完成");
        assertEquals(StageConstants.STAGE_GAMING, early.getStatus());
        assertNotNull(early.getMessage(), "未完成时必须给出可展示的原因");
        assertTrue(early.getMessage().contains("未结算"),
            "原因应说明还有场次未结算,实际: " + early.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "未打完时赛段必须保持进行中");

        finishByDirector(matchesOf(stage.getId()).get(0).getId());

        StageCompleteVo done = lifecycleService.completeStage(stage.getId());
        assertTrue(done.getCompleted());
        assertEquals(StageConstants.STAGE_SETTLED, done.getStatus());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
    }

    /**
     * 排名赛:有选手一条裁判分都没打时,同样是 completed=false + 点名到人,
     * 而不是抛异常。此前这里抛 ServiceException,导播台只能看到一个笼统的失败提示。
     */
    @Test
    void rankStageWithUnjudgedCompetitorsReportsPending() {
        Long tid = newTournament("排名赛未打分");
        TStageVo stage = newStage(tid, "排名赛", "RANK", 4, 2, rankRule());
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(stage.getId());

        // 一场都没打分就点完成
        StageCompleteVo vo = lifecycleService.completeStage(stage.getId());
        assertFalse(vo.getCompleted(), "选手未打分不应算完成");
        assertEquals(StageConstants.STAGE_GAMING, vo.getStatus());
        assertNotNull(vo.getMessage());
        assertTrue(vo.getMessage().contains("未打分"), "原因应说明有人没打分,实际: " + vo.getMessage());
        assertTrue(vo.getMessage().contains("选手1"), "原因应点名到人,实际: " + vo.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "未打分时赛段必须保持进行中");
    }

    /**
     * 擂台赛一场都没打完时,「尚未进行任何对决」也是 completed=false,
     * 不该让导播在点「完成赛段」时收到一个错误弹窗。
     *
     * <p>该状态由「首场对决回到待开始」构成。导播台的取消开始仅支持淘汰赛,
     * 这里直接把场次置回 PENDING 来构造该输入,测的是结算策略对这一状态的处理。</p>
     */
    @Test
    void arenaStageWithoutAnyBattleReportsPending() {
        Long tid = newTournament("擂台无对决");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 4, 1,
            "{\"mode\":\"ARENA\",\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(stage.getId());

        // 开赛会自动开第一场;把它置回待开始 → 进行中 0 场、已结算 0 场
        Long matchId = matchesOf(stage.getId()).get(0).getId();
        TMatch back = new TMatch();
        back.setId(matchId);
        back.setStatus(StageConstants.MATCH_PENDING);
        matchMapper.updateById(back);

        StageCompleteVo vo = lifecycleService.completeStage(stage.getId());
        assertFalse(vo.getCompleted(), "一场没打完不应算完成");
        assertNotNull(vo.getMessage());
        assertTrue(vo.getMessage().contains("尚未进行任何对决"),
            "原因应说明还没打过,实际: " + vo.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newKnockoutStage(Long tid, String name, int teams, int advance) {
        return newStage(tid, name, "KNOCKOUT", teams, advance,
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + teams
                + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\"}"
                + ",\"scoring\":{\"matchMode\":\"STANDARD\"}}");
    }

    /** 排名赛规则:单圈、MULTI_DIM 打分(RANK 走多裁判累计分,completeStage 时才统一结算) */
    private String rankRule() {
        return "{\"mode\":\"RANK\",\"circles\":1,\"advanceCount\":2,\"maxScore\":100,"
            + "\"scoring\":{\"type\":\"MULTI_DIM\",\"matchMode\":\"RANKING\","
            + "\"dimensions\":[{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":1,\"maxScore\":100}]}}";
    }

    private TStageVo newStage(Long tid, String name, String mode, int teams, int advance, String ruleConfig) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) teams);
        bo.setTeamCountEnd((long) advance);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(ruleConfig);
        return stageService.insertByBo(bo);
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

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
    }

    /** 导播台判定一场:按槽位顺序前者胜 */
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
}
