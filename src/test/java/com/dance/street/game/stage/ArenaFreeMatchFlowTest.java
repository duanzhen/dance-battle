package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.FreeMatchBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 擂台赛与自由对抗的状态流转:这两条此前完全没有测试覆盖
 * (startNextArenaMatch / computeArenaQueue / settleArenaStage / 弃权 /
 * createFreeMatch / deleteFreeMatch / selectFreeMatchAdvancers)。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ArenaFreeMatchFlowTest {

    private static final String DB_PATH = "target/arena-free-match-flow.db";

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

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name, String mode, int start, int end, String rule) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) start);
        bo.setTeamCountEnd((long) end);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(rule);
        return stageService.insertByBo(bo);
    }

    private Long insertPending(Long tid, Long stageId, String name, String number, int seed) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank((long) seed);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        return c.getId();
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> realParticipants(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    /** 擂台/自由对抗的场次创建时即为进行中,直接判定:slot1 胜 */
    private void finishInPlace(Long matchId) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        assertEquals(2, parts.size(), "对决应有 2 名参赛方");
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(parts.get(0).getCompetitorId(), "WIN");
        outcomes.put(parts.get(1).getCompetitorId(), "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    private static String arenaRule() {
        return "{\"mode\":\"ARENA\",\"scoring\":{\"matchMode\":\"STANDARD\"}}";
    }

    private static String freeMatchRule() {
        return "{\"mode\":\"FREE_MATCH\"}";
    }

    /** 擂台赛:开赛自动开第一场 → 胜者守擂 → 逐场轮转 → 完成赛段落冠军与名次。 */
    @Test
    void arenaRotatesQueueAndSettlesChampion() {
        Long tid = newTournament("擂台赛流转");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 4, 1, arenaRule());
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(1, matches.size(), "擂台赛开赛应自动创建第一场对决");
        assertEquals(StageConstants.MATCH_GAMING, matches.get(0).getStatus(),
            "擂台赛场次创建即为进行中");

        ArenaOverviewVo overview = lifecycleService.getArenaOverview(stage.getId());
        assertEquals(4, overview.getQueue().size(), "队列应含全部 4 名参赛者");
        List<TMatchParticipant> first = realParticipants(matches.get(0).getId());
        assertEquals(ids.get(0), first.get(0).getCompetitorId(), "队首为擂主");
        assertEquals(ids.get(1), first.get(1).getCompetitorId(), "队次为挑战者");

        // 第一场:擂主守擂成功
        finishInPlace(matches.get(0).getId());

        // 下一场:胜者继续守擂,败者回到队尾
        lifecycleService.startNextArenaMatch(stage.getId());
        matches = matchesOf(stage.getId());
        assertEquals(2, matches.size(), "应创建第二场对决");
        List<TMatchParticipant> second = realParticipants(matches.get(1).getId());
        assertEquals(ids.get(0), second.get(0).getCompetitorId(), "胜者应守擂");
        assertEquals(ids.get(2), second.get(1).getCompetitorId(), "败者排队尾,由下一位挑战");

        finishInPlace(matches.get(1).getId());

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "擂台赛只产生 1 名晋级(冠军)");
        assertEquals(3, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
        TCompetitor champion = competitorMapper.selectById(ids.get(0));
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), champion.getOutcomeStatus());
        assertEquals(Long.valueOf(1L), champion.getFinalRank(), "两连胜的擂主应为冠军");
    }

    /** 擂台赛弃权:临时弃权者被换下当前对决并排队尾;永久弃权者退出队列。 */
    @Test
    void arenaWithdrawReplacesAndExcludes() {
        Long tid = newTournament("擂台弃权");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 4, 1, arenaRule());
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }

        lifecycleService.startStage(stage.getId());
        TMatch current = matchesOf(stage.getId()).get(0);
        assertEquals(ids.get(1), realParticipants(current.getId()).get(1).getCompetitorId());

        // 临时弃权:当前对决里被替换,后续仍排队
        lifecycleService.tempWithdrawArenaCompetitor(stage.getId(), ids.get(1));
        List<TMatchParticipant> after = realParticipants(current.getId());
        assertEquals(2, after.size(), "补位后仍应是 2 人对决");
        assertTrue(after.stream().noneMatch(p -> ids.get(1).equals(p.getCompetitorId())),
            "临时弃权者应被换下当前对决");

        // 永久弃权:标记 WITHDRAWN,不再出现在队列中
        lifecycleService.withdrawArenaCompetitor(stage.getId(), ids.get(3));
        assertEquals(OutcomeStatusEnum.WITHDRAWN.getCode(),
            competitorMapper.selectById(ids.get(3)).getOutcomeStatus());
        ArenaOverviewVo overview = lifecycleService.getArenaOverview(stage.getId());
        assertTrue(overview.getQueue().stream()
                .noneMatch(q -> ids.get(3).equals(q.getCompetitorId())),
            "弃权者不应出现在队列中");
    }

    /** 自由对抗:开赛不生成对阵,由导播手动加场/删场,最后手动指定晋级者。 */
    @Test
    void freeMatchManualFlow() {
        Long tid = newTournament("自由对抗");
        TStageVo stage = newStage(tid, "自由对抗", "FREE_MATCH", 4, 1, freeMatchRule());
        Long a = insertPending(tid, stage.getId(), "选手A", "1", 1);
        Long b = insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long c = insertPending(tid, stage.getId(), "选手C", "3", 3);
        Long d = insertPending(tid, stage.getId(), "选手D", "4", 4);

        lifecycleService.startStage(stage.getId());
        assertEquals(0, matchesOf(stage.getId()).size(), "自由对抗开赛不生成任何对阵");

        // 手动添加一场对战
        Long m1 = lifecycleService.createFreeMatch(stage.getId(), a, b);
        assertEquals(StageConstants.MATCH_PENDING, matchMapper.selectById(m1).getStatus(),
            "手动添加的对战应为待开始");

        // 误加一场后删除
        Long m2 = lifecycleService.createFreeMatch(stage.getId(), c, d);
        lifecycleService.deleteFreeMatch(m2);
        assertNull(matchMapper.selectById(m2), "误加的对战应可删除");

        // 开始并判定第一场:A 胜
        matchResultService.startMatch(m1);
        finishInPlace(m1);
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(m1).getStatus());

        // 手动指定晋级者:A、C 晋级,其余淘汰
        int advanced = lifecycleService.selectFreeMatchAdvancers(stage.getId(), List.of(a, c));
        assertEquals(2, advanced, "手动指定 2 人晋级");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
        assertEquals(Long.valueOf(1L), competitorMapper.selectById(a).getFinalRank(),
            "指定顺序即种子顺序,首位名次为 1");

        // 非自由对抗赛段不允许手动加场
        TStageVo knockout = newStage(tid, "淘汰赛", "KNOCKOUT", 2, 1,
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":2,\"advanceCount\":1},"
                + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        assertThrows(RuntimeException.class,
            () -> lifecycleService.createFreeMatch(knockout.getId(), a, b),
            "非自由对抗赛段不允许手动加场");
    }
}
