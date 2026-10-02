package com.dance.street.game.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 裁判端判罚页接口:数据组装与圈级权限。
 *
 * <p>这块逻辑此前写在控制器里(400 余行)且没有任何测试覆盖,搬进
 * {@code IRefereeMatchService} 时补上:验证分圈海选下裁判只看到自己的圈、
 * 只能判自己的圈,以及未分配赛段时的提示。</p>
 *
 * @author duane
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RefereeMatchQueryTest {

    private static final String DB_PATH = "target/referee-match-query.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH
            + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private WebApplicationContext wac;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TRefereeStageMapper refereeStageMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private TMatchRoundMapper matchRoundMapper;
    @Autowired private TRoundScoreMapper roundScoreMapper;
    @Autowired private ITStageLifecycleService lifecycleService;

    /**
     * 分圈海选:圈1裁判只看到/只能判圈1;判圈2的场次被拒。
     * 同时覆盖 my-match 的组装(赛段/场次/参赛方/满分/赛段总览)。
     */
    @Test
    void circleRefereeOnlySeesAndJudgesOwnCircle() throws Exception {
        Long tid = newTournament("裁判端分圈", "tour-ref-circle");
        Long refA = newReferee(tid, "裁判A", "ref-key-a");
        Long refB = newReferee(tid, "裁判B", "ref-key-b");
        Long stageId = newAuditionWithTwoCircles(tid, refA, refB);
        lifecycleService.ensureAuditionCircles(stageId);

        List<TMatch> circles = circleMatches(stageId);
        assertEquals(2, circles.size(), "应建出两个圈");
        assertEquals(1, matchRefereeCount(circles.get(0).getId()), "圈1应绑定裁判A");
        assertEquals(1, matchRefereeCount(circles.get(1).getId()), "圈2应绑定裁判B");

        // 两个圈各签两人,开赛
        List<Long> circle1 = checkIn(tid, stageId, circles.get(0).getId(), "1", "2");
        List<Long> circle2 = checkIn(tid, stageId, circles.get(1).getId(), "3", "4");
        bindStage(tid, stageId, refA);
        bindStage(tid, stageId, refB);
        lifecycleService.startStage(stageId);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/game/referee-match/my-match")
                .header("Authorization", "Bearer ref-key-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.refereeName").value("裁判A"))
            .andExpect(jsonPath("$.data.stage.id").value(stageId.toString()))
            .andExpect(jsonPath("$.data.maxScore").value(100))
            .andExpect(jsonPath("$.data.participants.length()").value(2))
            // 圈级过滤:可判场次与赛段总览都只剩自己那一个圈
            .andExpect(jsonPath("$.data.matches.length()").value(1))
            .andExpect(jsonPath("$.data.matches[0].id").value(circles.get(0).getId().toString()))
            .andExpect(jsonPath("$.data.stageMatches.length()").value(1))
            .andExpect(jsonPath("$.data.stageMatches[0].id").value(circles.get(0).getId().toString()));

        // 判别人的圈:必须被拒
        mvc.perform(post("/game/referee-match/" + circles.get(1).getId() + "/submit-score")
                .header("Authorization", "Bearer ref-key-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":[{\"competitorId\":" + circle2.get(0) + ",\"score\":90}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("未分配该圈")));

        // 判自己的圈:落库,并且回显里带上"我打的分"
        mvc.perform(post("/game/referee-match/" + circles.get(0).getId() + "/submit-score")
                .header("Authorization", "Bearer ref-key-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":[{\"competitorId\":" + circle1.get(0) + ",\"score\":88}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        // 海选逐选手各占一个轮次,按本场全部轮次统计该裁判写下的分
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<com.dance.street.game.domain.TMatchRound>lambdaQuery()
                .eq(com.dance.street.game.domain.TMatchRound::getMatchId, circles.get(0).getId()))
            .stream().map(com.dance.street.game.domain.TMatchRound::getId).toList();
        long written = roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
            .in(TRoundScore::getRoundId, roundIds)
            .eq(TRoundScore::getRefereeId, refA)
            .eq(TRoundScore::getCompetitorId, circle1.get(0)));
        assertEquals(1, written, "圈1裁判对圈1选手的打分应落库");

        mvc.perform(get("/game/referee-match/my-match")
                .header("Authorization", "Bearer ref-key-a"))
            .andExpect(jsonPath("$.data.myScores.length()").value(1))
            .andExpect(jsonPath("$.data.myScores[0].competitorId").value(circle1.get(0).toString()));
    }

    /** 未分配任何赛段的裁判:提示去联系管理员,而不是返回空壳数据。 */
    @Test
    void refereeWithoutStageGetsClearMessage() throws Exception {
        Long tid = newTournament("裁判端未分配", "tour-ref-unassigned");
        newReferee(tid, "散人裁判", "ref-key-lone");

        MockMvcBuilders.webAppContextSetup(wac).build()
            .perform(get("/game/referee-match/my-match")
                .header("Authorization", "Bearer ref-key-lone"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("暂未分配赛段")));
    }

    // ===== 造数据 =====

    private Long newTournament(String name, String authKey) {
        TTournament t = new TTournament();
        t.setName(name);
        t.setAuthKey(authKey);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private Long newReferee(Long tid, String name, String authKey) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        r.setAuthKey(authKey);
        refereeMapper.insert(r);
        return r.getId();
    }

    /** 两圈海选:每圈晋级 1 人,裁判按下标一对一绑到圈上。 */
    private Long newAuditionWithTwoCircles(Long tid, Long refA, Long refB) {
        TStage stage = new TStage();
        stage.setTournamentId(tid);
        stage.setName("海选");
        stage.setStageMode("AUDITION");
        stage.setStatus(StageConstants.STAGE_DRAFT);
        stage.setTeamCountStart(0L);
        stage.setTeamCountEnd(2L);
        stage.setIsInitialized(0L);
        stage.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":2,"
            + "\"circleAdvanceCounts\":[1,1],\"maxScore\":100,"
            + "\"circleRefereeIds\":[[\"" + refA + "\"],[\"" + refB + "\"]],"
            + "\"scoring\":{\"type\":\"TOTAL_SCORE\",\"matchMode\":\"VOTING\"}}");
        stageMapper.insert(stage);
        return stage.getId();
    }

    private List<TMatch> circleMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    /** 按号码建参赛方并落进指定圈(走真实补签到入口)。 */
    private List<Long> checkIn(Long tid, Long stageId, Long matchId, String... numbers) {
        List<Long> ids = new java.util.ArrayList<>();
        for (String no : numbers) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("选手" + no);
            c.setNumber(no);
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
            lifecycleService.appendStageCompetitor(stageId, c.getId(), matchId);
            ids.add(c.getId());
        }
        return ids;
    }

    /** 裁判-赛段绑定(裁判端据此找到自己负责的赛段)。 */
    private void bindStage(Long tid, Long stageId, Long refereeId) {
        TRefereeStage rs = new TRefereeStage();
        rs.setTournamentId(tid);
        rs.setStageId(stageId);
        rs.setRefereeId(refereeId);
        refereeStageMapper.insert(rs);
    }

    private long matchRefereeCount(Long matchId) {
        return matchRefereeMapper.selectCount(Wrappers.<com.dance.street.game.domain.TMatchReferee>lambdaQuery()
            .eq(com.dance.street.game.domain.TMatchReferee::getMatchId, matchId));
    }
}
