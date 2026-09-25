package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITPlayerService;
import com.dance.street.game.service.ITStageLifecycleService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选开赛后继续加人(现场补签到)。
 *
 * <p>现场最常见的场景:海选已经开判,迟到选手/临时报名者到场,需要在不动已判结果的前提下
 * 把他们加进某个还没判完的圈。这个场景此前没有测试覆盖——既有用例只覆盖「开赛前签到」
 * 与「开赛后禁止改号/解除签到」。</p>
 *
 * <p>口径:开赛后只允许<b>新增</b>签到({@code appendStageCompetitor} 支持 GAMING),
 * 已签到者的改号/换圈/解除一律锁定;落圈必须由客户端指定,后端不推导。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionLateCheckInTest {

    private static final String DB_PATH = "target/audition-late-check-in.db";

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
    private TMatchRoundMapper matchRoundMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TRefereeMapper refereeMapper;
    @Autowired
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private TPlayerMapper playerMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITPlayerService playerService;
    @Autowired
    private ITMatchResultService matchResultService;

    /** 开赛后补签到:落在指定圈、不影响其他圈、并且拿得到轮次(裁判能打分)。 */
    @Test
    void lateCheckInLandsInTargetCircleWhileGaming() {
        Long tid = newTournament("开赛后补签到");
        TStageVo stage = startAudition(tid, new int[]{1, 1});
        List<TMatch> circles = circlesOf(stage.getId());
        Long circle1 = circles.get(0).getId();
        Long circle2 = circles.get(1).getId();

        Long c1 = putCompetitor(tid, stage, circle1, "老选手1", "1");
        Long c4 = putCompetitor(tid, stage, circle2, "老选手4", "4");

        bindRefereeToAllCircles(tid, stage.getId());
        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        Long latePlayer = newPlayer(tid, "迟到者");
        TPlayerVo vo = playerService.checkIn(checkInBo(latePlayer, "5", circle2));
        assertNotNull(vo.getCompetitorId(), "开赛后补签到应成功并绑定参赛单位");
        Long late = vo.getCompetitorId();

        assertEquals(circle2, circleOf(stage.getId(), late), "迟到者应落在客户端指定的第 2 圈");
        assertEquals(0, participantCount(circle1, late), "不应影响已开判的第 1 圈");
        assertEquals(1, participantCount(circle2, late), "应挂入第 2 圈");
        assertEquals(1, roundCount(circle2, late), "补签到应同时建好轮次,裁判才能打分");

        assertEquals(1, participantCount(circle1, c1), "既有选手的落圈不受影响");
        assertEquals(1, participantCount(circle2, c4));
        assertEquals("1", number(c1));
        assertEquals(3, competitorCount(stage.getId()), "赛段内应有 3 名参赛方(2 名既有 + 1 名迟到)");
    }

    /** 迟到者与老选手同池竞争:分数高就能拿到晋级名额。 */
    @Test
    void lateCheckInCompetesForAdvancement() {
        Long tid = newTournament("迟到者参与结算");
        TStageVo stage = startAudition(tid, new int[]{1, 1});
        List<TMatch> circles = circlesOf(stage.getId());
        Long circle1 = circles.get(0).getId();
        Long circle2 = circles.get(1).getId();

        Long c1 = putCompetitor(tid, stage, circle1, "老选手1", "1");
        Long c2 = putCompetitor(tid, stage, circle1, "老选手2", "2");
        Long c3 = putCompetitor(tid, stage, circle2, "老选手3", "3");
        Long c4 = putCompetitor(tid, stage, circle2, "老选手4", "4");

        bindRefereeToAllCircles(tid, stage.getId());
        lifecycleService.startStage(stage.getId());

        Long latePlayer = newPlayer(tid, "迟到者");
        Long late = playerService.checkIn(checkInBo(latePlayer, "5", circle2)).getCompetitorId();

        score(circle1, c1, "9");
        score(circle1, c2, "5");
        score(circle2, c3, "9");
        score(circle2, c4, "5");
        score(circle2, late, "9.5");

        StageCompleteVo result = lifecycleService.completeStage(stage.getId());
        assertTrue(result.getCompleted(), "全员判完后应能结束,实际: " + result.getMessage());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());

        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), outcome(late), "迟到者分数最高,应晋级");
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), outcome(c1), "第 1 圈第一名晋级");
        assertEquals(OutcomeStatusEnum.ELIMINATED.getCode(), outcome(c3), "被迟到者挤掉的应是同圈对手");
        assertEquals(OutcomeStatusEnum.ELIMINATED.getCode(), outcome(c4));
    }

    /** 加赛中间态:正式圈已结算,此时补签到无处可落,应明确拒绝且不留脏数据。 */
    @Test
    void lateCheckInRejectedWhenNoOpenCircle() {
        Long tid = newTournament("加赛期间补签到");
        TStageVo stage = startAudition(tid, new int[]{1});
        Long circle = circlesOf(stage.getId()).get(0).getId();

        Long c1 = putCompetitor(tid, stage, circle, "老选手1", "1");
        Long c2 = putCompetitor(tid, stage, circle, "老选手2", "2");

        bindRefereeToAllCircles(tid, stage.getId());
        lifecycleService.startStage(stage.getId());

        score(circle, c1, "9");
        score(circle, c2, "9");
        StageCompleteVo result = lifecycleService.completeStage(stage.getId());
        assertFalse(result.getCompleted());
        assertTrue(result.getTiebreaker(), "应进入加赛中间态,实际: " + result.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        long before = competitorCount(stage.getId());
        Long latePlayer = newPlayer(tid, "迟到者");
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(latePlayer, "5", circle)));
        assertTrue(ex.getMessage().contains("无法签到"), "应明确告知无圈可落,实际: " + ex.getMessage());
        assertEquals(before, competitorCount(stage.getId()), "被拒后不应残留参赛单位(事务应整体回滚)");
        assertNull(playerMapper.selectById(latePlayer).getCompetitorId(), "选手不应被绑定到参赛单位");
    }

    /** 赛段已结束:迟到者不再被接受。 */
    @Test
    void lateCheckInRejectedAfterStageSettled() {
        Long tid = newTournament("结束后补签到");
        TStageVo stage = startAudition(tid, new int[]{1});
        Long circle = circlesOf(stage.getId()).get(0).getId();

        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);

        Long latePlayer = newPlayer(tid, "迟到者");
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(latePlayer, "5", circle)));
        assertTrue(ex.getMessage().contains("无法继续签到"), "实际: " + ex.getMessage());
    }

    /** 多圈开赛后补签到仍需指定圈:后端不代选,避免同批号码因调用顺序落到不同圈。 */
    @Test
    void lateCheckInStillRequiresTargetCircleWhileGaming() {
        Long tid = newTournament("开赛后未指定圈");
        TStageVo stage = startAudition(tid, new int[]{1, 1});
        bindRefereeToAllCircles(tid, stage.getId());
        lifecycleService.startStage(stage.getId());

        Long latePlayer = newPlayer(tid, "迟到者");
        CheckInBo bo = new CheckInBo();
        bo.setPlayerId(latePlayer);
        bo.setCheckInType("CREATE");
        bo.setCompetitorNumber("5");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> playerService.checkIn(bo));
        assertTrue(ex.getMessage().contains("请指定落圈"), "实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 造数据
    // ------------------------------------------------------------------

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    /** 建一个多圈海选(首赛段)+ 预建空圈。 */
    private TStageVo startAudition(Long tid, int[] circleQuotas) {
        int circles = circleQuotas.length;
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("海选");
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) circles);
        bo.setIsInitialized(0L);
        StringBuilder quotas = new StringBuilder("[");
        int total = 0;
        for (int i = 0; i < circles; i++) {
            quotas.append(i > 0 ? "," : "").append(circleQuotas[i]);
            total += circleQuotas[i];
        }
        quotas.append("]");
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":" + circles
            + ",\"advanceCount\":" + total + ",\"maxScore\":100,\"circleAdvanceCounts\":"
            + quotas + "}");
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return stage;
    }

    private Long newPlayer(Long tid, String name) {
        TPlayer p = new TPlayer();
        p.setTournamentId(tid);
        p.setName(name);
        playerMapper.insert(p);
        return p.getId();
    }

    /** 直接建参赛方并挂入指定圈(绕过签到入口,用于铺开赛前的既有选手)。 */
    private Long putCompetitor(Long tid, TStageVo stage, Long circleId, String name, String number) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), circleId);
        return c.getId();
    }

    private void bindRefereeToAllCircles(Long tournamentId, Long stageId) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName("裁判A");
        refereeMapper.insert(r);
        for (TMatch m : circlesOf(stageId)) {
            TMatchReferee mr = new TMatchReferee();
            mr.setMatchId(m.getId());
            mr.setRefereeId(r.getId());
            mr.setTournamentId(tournamentId);
            matchRefereeMapper.insert(mr);
        }
    }

    private CheckInBo checkInBo(Long playerId, String number, Long matchId) {
        CheckInBo bo = new CheckInBo();
        bo.setPlayerId(playerId);
        bo.setCheckInType("CREATE");
        bo.setCompetitorNumber(number);
        bo.setMatchId(matchId);
        return bo;
    }

    /** 裁判给某位选手打分(海选按圈累计,显式关掉自动收尾)。 */
    private void score(Long matchId, Long competitorId, String value) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(null);
        bo.setFinalizeStageIfComplete(false);
        ScoreEntryBo entry = new ScoreEntryBo();
        entry.setCompetitorId(competitorId);
        entry.setDimension("MAIN");
        entry.setAction("SCORE");
        entry.setScore(new BigDecimal(value));
        bo.setScores(List.of(entry));
        matchResultService.submitResult(bo);
    }

    private List<TMatch> circlesOf(Long stageId) {
        return new ArrayList<>(matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId)));
    }

    private Long circleOf(Long stageId, Long competitorId) {
        for (TMatch m : circlesOf(stageId)) {
            if (participantCount(m.getId(), competitorId) > 0) {
                return m.getId();
            }
        }
        return null;
    }

    private long participantCount(Long matchId, Long competitorId) {
        return participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
    }

    private long roundCount(Long matchId, Long competitorId) {
        return matchRoundMapper.selectCount(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, matchId)
            .eq(TMatchRound::getCompetitorId, competitorId));
    }

    private long competitorCount(Long stageId) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
    }

    private String number(Long competitorId) {
        return competitorMapper.selectById(competitorId).getNumber();
    }

    private String outcome(Long competitorId) {
        return competitorMapper.selectById(competitorId).getOutcomeStatus();
    }
}
