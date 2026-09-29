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

    /**
     * 临时弃权只跳过一轮:该选手排到队尾后仍会回到轮转队列上场比赛。
     *
     * <p>回归:旧实现每次重算队列都把弃权者摘出队列、再追加到末尾,而标记永不过期,
     * 导致被临时弃权的人永远排在队尾、再也上不了场。</p>
     */
    @Test
    void arenaTempWithdrawSkipsOneRoundThenReturns() {
        Long tid = newTournament("擂台临时弃权回归");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 4, 1, arenaRule());
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }
        Long skipped = ids.get(1);

        lifecycleService.startStage(stage.getId());
        TMatch first = matchesOf(stage.getId()).get(0);
        assertEquals(skipped, realParticipants(first.getId()).get(1).getCompetitorId(),
            "被临时弃权的应是队次(挑战者)");

        lifecycleService.tempWithdrawArenaCompetitor(stage.getId(), skipped);

        ArenaOverviewVo now = lifecycleService.getArenaOverview(stage.getId());
        assertEquals(skipped, now.getQueue().get(now.getQueue().size() - 1).getCompetitorId(),
            "临时弃权当下应排到队尾");

        boolean playedAgain = false;
        for (int round = 0; round < 6 && !playedAgain; round++) {
            TMatch gaming = matchesOf(stage.getId()).stream()
                .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                .findFirst()
                .orElse(null);
            if (gaming == null) {
                lifecycleService.startNextArenaMatch(stage.getId());
                gaming = matchesOf(stage.getId()).stream()
                    .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                    .findFirst()
                    .orElseThrow();
            }
            playedAgain = realParticipants(gaming.getId()).stream()
                .anyMatch(p -> skipped.equals(p.getCompetitorId()));
            if (!playedAgain) {
                finishInPlace(gaming.getId());
            }
        }
        assertTrue(playedAgain, "临时弃权的选手应在若干场后重新上场,不能被永久钉在队尾");
    }

    /**
     * 原定 8 人擂台、实到 7 人:擂台赛不生成对阵树,也就没有"第 8 个位置"要填,
     * 直接按实际签到人数轮转即可正常进行(与淘汰赛不同,不会产生轮空场次)。
     *
     * <p>断言:队伍始终 7 人、6 场之内 7 人全部上过场、结算落 1..7 名(1 冠军 + 6 淘汰)。</p>
     */
    @Test
    void arenaWithSevenOfPlannedEightRunsFullRotation() {
        Long tid = newTournament("擂台8缺1");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 8, 1, arenaRule());
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }

        lifecycleService.startStage(stage.getId());
        assertEquals(7, lifecycleService.getArenaOverview(stage.getId()).getQueue().size(),
            "队列应为实际签到的 7 人");

        java.util.Set<Long> played = new java.util.HashSet<>();
        // 每场都让挑战者胜,队列整体前移一位,6 场可让 7 人各上一次场
        for (int round = 0; round < 6; round++) {
            TMatch gaming = matchesOf(stage.getId()).stream()
                .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                .findFirst()
                .orElse(null);
            if (gaming == null) {
                lifecycleService.startNextArenaMatch(stage.getId());
                gaming = matchesOf(stage.getId()).stream()
                    .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                    .findFirst()
                    .orElseThrow();
            }
            List<TMatchParticipant> parts = realParticipants(gaming.getId());
            assertEquals(2, parts.size(), "擂台赛每场都是 2 人对决,不存在轮空");
            played.add(parts.get(0).getCompetitorId());
            played.add(parts.get(1).getCompetitorId());

            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(gaming.getId());
            Map<Long, String> outcomes = new HashMap<>();
            outcomes.put(parts.get(0).getCompetitorId(), "LOSS");
            outcomes.put(parts.get(1).getCompetitorId(), "WIN");
            bo.setOutcomes(outcomes);
            matchResultService.submitResult(bo);

            assertEquals(7, lifecycleService.getArenaOverview(stage.getId()).getQueue().size(),
                "每场之后队列都不应丢人");
        }
        assertEquals(7, played.size(), "6 场之内 7 个人都应上过场");

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "擂台赛只产生 1 名冠军晋级");
        assertEquals(6, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        java.util.Set<Long> ranks = new java.util.TreeSet<>();
        for (TCompetitor c : competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()))) {
            ranks.add(c.getFinalRank());
        }
        assertEquals(new java.util.TreeSet<>(java.util.List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)), ranks,
            "7 人应落 1..7 名且不重不漏");
    }

    /**
     * 平局计分:默认「双方各 +1 分」;赛段配置显式 drawBothScore=false 时才回到"平局都不加分"。
     *
     * <p>回归:此前默认不加分,赛段配置里没写 drawBothScore 的赛事会被静默判成"平局白打"。</p>
     */
    @Test
    void arenaDrawScoresBothByDefaultAndCanBeDisabled() {
        // 默认:ruleConfig 里不写 drawBothScore → 平局双方各 +1
        Long tid = newTournament("擂台平局默认加分");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 4, 1,
            "{\"mode\":\"ARENA\",\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }
        lifecycleService.startStage(stage.getId());
        drawCurrentMatch(stage.getId());

        ArenaOverviewVo byDefault = lifecycleService.getArenaOverview(stage.getId());
        List<Long> firstPair = List.of(ids.get(0), ids.get(1));
        for (ArenaOverviewVo.CompetitorInfo c : byDefault.getQueue()) {
            int expected = firstPair.contains(c.getCompetitorId()) ? 1 : 0;
            assertEquals(expected, c.getPoints(),
                "默认平局双方各 +1 分,未上场的人 0 分(选手" + c.getCompetitorId() + ")");
        }

        // 显式关闭:drawBothScore=false → 平局都不加分
        Long tid2 = newTournament("擂台平局不加分");
        TStageVo stage2 = newStage(tid2, "擂台赛", "ARENA", 4, 1,
            "{\"mode\":\"ARENA\",\"drawBothScore\":false,\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        for (int i = 1; i <= 4; i++) {
            insertPending(tid2, stage2.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(stage2.getId());
        drawCurrentMatch(stage2.getId());

        ArenaOverviewVo disabled = lifecycleService.getArenaOverview(stage2.getId());
        assertTrue(disabled.getQueue().stream().allMatch(c -> c.getPoints() == null || c.getPoints() == 0),
            "关闭平局加分后,平局双方都不得分");
    }

    /** 把当前进行中的对决判成平局(擂台赛不重赛,双方排队尾)。 */
    private void drawCurrentMatch(Long stageId) {
        TMatch gaming = matchesOf(stageId).stream()
            .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("没有进行中的对决"));
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(gaming.getId());
        Map<Long, String> outcomes = new HashMap<>();
        for (TMatchParticipant p : realParticipants(gaming.getId())) {
            outcomes.put(p.getCompetitorId(), "DRAW");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 原定 8 人名单全部到位、开赛前 1 人弃权:应按实到 7 人正常开赛。 */
    @Test
    void arenaWithOneWithdrawnBeforeStartRunsWithSeven() {
        Long tid = newTournament("擂台8缺1弃权");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA", 8, 1, arenaRule());
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i));
        }
        Long absent = ids.get(7);
        lifecycleService.withdrawArenaCompetitor(stage.getId(), absent);

        lifecycleService.startStage(stage.getId());
        ArenaOverviewVo overview = lifecycleService.getArenaOverview(stage.getId());
        assertEquals(7, overview.getQueue().size(), "1 人弃权后队列应为 7 人");
        assertTrue(overview.getQueue().stream().noneMatch(q -> absent.equals(q.getCompetitorId())),
            "弃权者不应出现在队列里");

        TMatch first = matchesOf(stage.getId()).get(0);
        assertEquals(StageConstants.MATCH_GAMING, first.getStatus());
        assertEquals(2, realParticipants(first.getId()).size(), "开赛后应正常开出第一场 2 人对决");
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
