package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参赛人数不足时的轮空链路。
 *
 * <p>海选允许"晋级不满原定人数":原定 16 人晋级但实到 15 人时,15 人全部晋级、第 16 个位置空缺,
 * 下一赛段的 16 人签表就出现 1 场轮空;轮空场次点「开始」当场出结果,胜者直接晋级。
 * 若整个半区都没人(双方都轮空),则该场次结算但不产生晋级者,下一赛段对应位置继续轮空。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionShortfallByeTest {

    private static final String DB_PATH = "target/audition-shortfall-bye.db";

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

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private ITMatchResultService matchResultService;

    /** 海选原定 16 人晋级但只来了 15 人 → 15 人全晋级,下一赛段 16 签表 1 场轮空并当场出结果。 */
    @Test
    void auditionShortfallLeavesOneByeInNextStage() {
        Long tid = newTournament("海选缺人");
        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 16L, null,
            "{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":16,\"maxScore\":100,"
                + "\"circleAdvanceCounts\":[16]}");
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, audition.getId(),
            knockoutRule(16, 8, false));

        lifecycleService.ensureAuditionCircles(audition.getId());
        Long judge = insertReferee(tid, "裁判");
        TMatch circle = circlesOf(audition.getId()).get(0);
        bindReferee(circle.getId(), judge, tid);
        for (int i = 1; i <= 15; i++) {
            putPlayer(tid, audition, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(audition.getId());
        scoreCircle(circle, judge);

        assertTrue(lifecycleService.completeStage(audition.getId()).getCompleted());
        assertEquals(15, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "原定 16 人、实到 15 人:15 人应全部晋级,不报错也不补人");
        assertEquals(0, countOutcome(audition.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        assertEquals(15, rosterService.applyRoster(round16.getId(), null), "15 人应全部带入下一赛段");
        lifecycleService.startStage(round16.getId());

        List<TMatch> matches = matchesOf(round16.getId());
        assertEquals(8, matches.size(), "16 人签表应为 8 场");
        List<TMatch> byes = matches.stream()
            .filter(m -> realParticipants(m.getId()).size() == 1).toList();
        List<TMatch> real = matches.stream()
            .filter(m -> realParticipants(m.getId()).size() == 2).toList();
        assertEquals(1, byes.size(), "15 人进 16 签表应恰好 1 场轮空");
        assertEquals(7, real.size());

        // 轮空场次:点「开始」当场出结果,胜者直接晋级下一赛段
        TMatch bye = byes.get(0);
        matchResultService.startMatch(bye.getId());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(bye.getId()).getStatus(),
            "轮空场次开始后应直接出结果");
        TMatchParticipant winner = realParticipants(bye.getId()).get(0);
        assertEquals("WIN", winner.getOutcomeStatus(), "轮空方应判胜");
        TCompetitor winnerCompetitor = competitorMapper.selectById(winner.getCompetitorId());
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), winnerCompetitor.getOutcomeStatus(),
            "轮空胜者应直接晋级下一赛段");
        assertNotNull(winnerCompetitor.getFinalRank(), "轮空胜者要带名次(下一赛段种子依据)");

        for (TMatch m : real) {
            finishByDirector(m.getId());
        }
        assertTrue(lifecycleService.completeStage(round16.getId()).getCompleted());
        assertEquals(8, countOutcome(round16.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "7 场真人场 + 1 场轮空 = 8 人晋级");
    }

    /** 整个半区都没人:轮空一路推进,空场次结算但不产生晋级者。 */
    @Test
    void emptyRegionChainsByesThroughStages() {
        Long tid = newTournament("连续轮空");
        TStageVo r8 = createStage(tid, "8强", "KNOCKOUT", 8L, 4L, null, knockoutRule(8, 4, false));
        TStageVo r4 = createStage(tid, "4强", "KNOCKOUT", 4L, 2L, r8.getId(), knockoutRule(4, 2, false));
        TStageVo fin = createStage(tid, "决赛", "KNOCKOUT", 2L, 1L, r4.getId(), knockoutRule(2, 1, false));
        insertCompetitor(tid, r8.getId(), "独苗", "1", 1);

        // 8强:1 人实到 + 3 场双方都轮空
        lifecycleService.startStage(r8.getId());
        settleByeMatchesOf(r8.getId());
        assertEquals(3, matchesOf(r8.getId()).stream()
            .filter(m -> realParticipants(m.getId()).isEmpty()).count(), "8 签表应有 3 场双方轮空");
        assertTrue(lifecycleService.completeStage(r8.getId()).getCompleted());
        assertEquals(1, countOutcome(r8.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "只有实到的那 1 人晋级,空场次不产生晋级者");

        // 4强 / 决赛:对手继续轮空,独苗一路走到底
        assertEquals(1, rosterService.applyRoster(r4.getId(), null));
        lifecycleService.startStage(r4.getId());
        settleByeMatchesOf(r4.getId());
        assertTrue(lifecycleService.completeStage(r4.getId()).getCompleted());
        assertEquals(1, countOutcome(r4.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        assertEquals(1, rosterService.applyRoster(fin.getId(), null));
        lifecycleService.startStage(fin.getId());
        settleByeMatchesOf(fin.getId());
        assertTrue(lifecycleService.completeStage(fin.getId()).getCompleted());
        assertEquals(1, countOutcome(fin.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "独苗应在决赛以轮空方式晋级");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(fin.getId()).getStatus());
    }

    // ===== 工具 =====

    /** 多圈同样允许名额不满:两圈各 8 个名额,第 2 圈只到了 7 人 → 全场 15 人晋级(不报错)。 */
    @Test
    void multiCircleAuditionAllowsPerCircleShortfall() {
        Long tid = newTournament("多圈缺人");
        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 16L, null,
            "{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":16,\"maxScore\":100,"
                + "\"circleAdvanceCounts\":[8,8]}");
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, audition.getId(),
            knockoutRule(16, 8, false));

        lifecycleService.ensureAuditionCircles(audition.getId());
        List<TMatch> circles = circlesOf(audition.getId());
        assertEquals(2, circles.size());
        Long judge1 = insertReferee(tid, "圈1裁判");
        Long judge2 = insertReferee(tid, "圈2裁判");
        bindReferee(circles.get(0).getId(), judge1, tid);
        bindReferee(circles.get(1).getId(), judge2, tid);
        for (int i = 1; i <= 15; i++) {
            putPlayer(tid, audition, "选手" + i, String.valueOf(i),
                circles.get(i <= 8 ? 0 : 1).getId());
        }
        lifecycleService.startStage(audition.getId());
        scoreCircle(circles.get(0), judge1);
        scoreCircle(circles.get(1), judge2);

        assertTrue(lifecycleService.completeStage(audition.getId()).getCompleted());
        assertEquals(8, countCircleAdvancers(circles.get(0).getId()), "第 1 圈 8 人满额晋级");
        assertEquals(7, countCircleAdvancers(circles.get(1).getId()),
            "第 2 圈只有 7 人,名额不满也应全部晋级,而不是报错或硬凑");
        assertEquals(15, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        assertEquals(15, rosterService.applyRoster(round16.getId(), null));
        lifecycleService.startStage(round16.getId());
        assertEquals(1, matchesOf(round16.getId()).stream()
                .filter(m -> realParticipants(m.getId()).size() == 1).count(),
            "15 人进 16 签表应恰好 1 场轮空");
    }

    private long countCircleAdvancers(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matchId)
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
                .isNotNull(TMatchParticipant::getCompetitorId))
            .stream().map(TMatchParticipant::getCompetitorId).distinct().count();
    }

    /** 一个都没晋级(海选无人/全退赛)时,下一赛段应能空过,不能卡在"无可初始化的参赛方"。 */
    @Test
    void noAdvancersStillLetsNextStagePlayThrough() {
        Long tid = newTournament("无人晋级");
        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 16L, null,
            "{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":16,\"maxScore\":100,"
                + "\"circleAdvanceCounts\":[16]}");
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, audition.getId(),
            knockoutRule(16, 8, false));
        lifecycleService.ensureAuditionCircles(audition.getId());
        bindReferee(circlesOf(audition.getId()).get(0).getId(), insertReferee(tid, "裁判"), tid);

        // 海选无人上场:圈场次空场结算,0 人晋级
        lifecycleService.startStage(audition.getId());
        assertTrue(lifecycleService.completeStage(audition.getId()).getCompleted());
        assertEquals(0, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        assertEquals(0, rosterService.applyRoster(round16.getId(), null), "没有人可带入下一赛段");
        lifecycleService.startStage(round16.getId());
        assertTrue(lifecycleService.completeStage(round16.getId()).getCompleted(),
            "空赛段应能直接完成,而不是报'赛段无可初始化的参赛方'");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(round16.getId()).getStatus());
    }

    /** 逐个"开始"轮空场次(小于 2 名真人):开始后必须当场出结果 */
    private void settleByeMatchesOf(Long stageId) {
        for (TMatch m : matchesOf(stageId)) {
            if (realParticipants(m.getId()).size() >= 2) {
                continue;
            }
            matchResultService.startMatch(m.getId());
            assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(m.getId()).getStatus(),
                m.getName() + " 轮空开始后应直接出结果");
        }
    }

    private void finishByDirector(Long matchId) {
        matchResultService.startMatch(matchId);
        List<TMatchParticipant> parts = realParticipants(matchId);
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

    private void scoreCircle(TMatch circle, Long refereeId) {
        List<TMatchParticipant> parts = realParticipants(circle.getId());
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(circle.getId());
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(BigDecimal.valueOf(100 - i));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    private List<TMatchParticipant> realParticipants(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchesOf(stageId);
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo createStage(Long tid, String name, String mode, Long start, Long end,
                                 Long prevId, String rule) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setPrevStageId(prevId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(rule);
        return stageService.insertByBo(bo);
    }

    private String knockoutRule(int teams, int advance, boolean thirdPlace) {
        return "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\""
            + (thirdPlace ? ",\"thirdPlaceMatch\":true" : "") + "},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}";
    }

    private void insertCompetitor(Long tid, Long stageId, String name, String number, int seed) {
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

    private void putPlayer(Long tid, TStageVo stage, String name, String number) {
        putPlayer(tid, stage, name, number, circlesOf(stage.getId()).get(0).getId());
    }

    private void putPlayer(Long tid, TStageVo stage, String name, String number, Long circleMatchId) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), circleMatchId);
    }

    private Long insertReferee(Long tid, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private void bindReferee(Long matchId, Long refereeId, Long tid) {
        TMatchReferee mr = new TMatchReferee();
        mr.setMatchId(matchId);
        mr.setRefereeId(refereeId);
        mr.setTournamentId(tid);
        matchRefereeMapper.insert(mr);
    }
}
