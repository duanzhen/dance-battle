package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.MatchRoundScoreVo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.engine.common.ScorePublishState;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.IRefereeMatchService;
import com.dance.street.game.service.impl.WidgetScoreDetailService;
import com.dance.street.game.service.impl.WidgetScoreDetailStore;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.ScreenResultMasker;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 排名赛打分公布(AUTO/MANUAL/BATCH)以及淘汰赛 MANUAL 公共接口遮罩。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RankScorePublishTest {

    private static final String DB_PATH = "target/rank-score-publish.db";

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

    @Autowired private WebApplicationContext wac;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITMatchService matchService;
    @Autowired private ITRefereeStageService refereeStageService;
    @Autowired private IRefereeMatchService refereeMatchService;
    @Autowired private ScreenResultMasker screenResultMasker;
    @Autowired private WidgetScoreDetailService widgetScoreDetailService;
    @Autowired private TVisSceneMapper visSceneMapper;
    @Autowired private TVisWidgetMapper visWidgetMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).build();
    }

    /** MANUAL:全部打完后场次进入待公布,不结算赛段;公共接口隐藏分数但标记已打;公布后露分且场次仍 GAMING。 */
    @Test
    void manualRankHoldsThenPublishesWithoutSettling() throws Exception {
        Long tid = newTournament("排名赛手动公布");
        var stage = newRankStage(tid, "排名手动", 2, 1, "MANUAL");
        Long judge = assignReferee(tid, stage.getId(), "裁判A");
        insertPending(tid, stage.getId(), "选手1", "1", 1);
        insertPending(tid, stage.getId(), "选手2", "2", 2);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        List<TMatchParticipant> parts = realParticipants(match.getId());
        submitTech(match.getId(), judge, parts, 90);

        TMatch pending = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, pending.getStatus(), "打完不应结算场次");
        assertEquals(ScorePublishState.PENDING_JSON, pending.getResultJson(), "应写入待公布标记");
        assertEquals(StageConstants.STAGE_GAMING, stageService.queryById(stage.getId()).getStatus(),
            "待公布时赛段仍进行中");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "公布打分不得触发晋级");

        TMatchVo director = directorMatch(stage.getId(), match.getId());
        assertTrue(Boolean.TRUE.equals(director.getPendingPublish()), "导播台应看到待公布");
        assertNotNull(director.getRoundScores().get(0).getScore(), "导播台仍看真实总分");
        assertEquals(0, new BigDecimal("90").compareTo(director.getRoundScores().get(0).getScore()));

        TMatchVo publicVo = copyAndMask(director);
        assertNull(publicVo.getResultJson(), "公共接口不得返回 resultJson");
        assertNull(publicVo.getRoundScores().get(0).getScore(), "公布前公共端不露总分");
        MatchRoundScoreVo.DimensionScore dim = publicVo.getRoundScores().get(0).getDimensions().get(0);
        assertNull(dim.getScore(), "公布前不露维度分");
        assertTrue(Boolean.TRUE.equals(dim.getRefereeScores().get(0).getScored()), "应标记该裁判已打");
        assertNull(dim.getRefereeScores().get(0).getScore(), "已打格子不得带出分数");

        RankDetailVo hiddenDetail = lifecycleService.getRankDetail(stage.getId());
        assertNull(hiddenDetail.getCircles().get(0).getCompetitors().get(0).getScoreValue(),
            "rank-detail 公布前应隐藏分数");
        assertNull(hiddenDetail.getCircles().get(0).getCompetitors().get(0).getRankInMatch(),
            "rank-detail 公布前应隐藏名次");

        mvc().perform(get("/tournament/screen/match/" + match.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultJson").value(nullValue()))
            .andExpect(jsonPath("$.data.roundScores[0].score").value(nullValue()))
            .andExpect(jsonPath("$.data.roundScores[0].dimensions[0].score").value(nullValue()))
            .andExpect(jsonPath("$.data.roundScores[0].dimensions[0].refereeScores[0].scored").value(true))
            .andExpect(jsonPath("$.data.roundScores[0].dimensions[0].refereeScores[0].score").value(nullValue()));

        var published = matchResultService.publishResult(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, published.getStatus(), "公布后场次仍进行中");
        TMatch after = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, after.getStatus());
        assertEquals(ScorePublishState.PUBLISHED_JSON, after.getResultJson());
        assertEquals(StageConstants.STAGE_GAMING, stageService.queryById(stage.getId()).getStatus(),
            "公布打分不得完成赛段");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertFalse(Boolean.TRUE.equals(directorMatch(stage.getId(), match.getId()).getPendingPublish()),
            "已公布后不应再显示待公布按钮");

        TMatchVo publicAfter = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertEquals(0, new BigDecimal("90").compareTo(publicAfter.getRoundScores().get(0).getScore()),
            "公布后公共端应露分");
        RankDetailVo shown = lifecycleService.getRankDetail(stage.getId());
        assertNotNull(shown.getCircles().get(0).getCompetitors().get(0).getScoreValue(),
            "已公布但未结算时 rank-detail 应露分");

        mvc().perform(get("/tournament/screen/match/" + match.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roundScores[0].score").value(90));

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageService.queryById(stage.getId()).getStatus());
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "完成赛段后才晋级");
    }

    /** AUTO:边打边在公共接口露分,不进入待公布。 */
    @Test
    void autoRankExposesScoresLive() {
        Long tid = newTournament("排名赛自动公布");
        var stage = newRankStage(tid, "排名自动", 2, 1, "AUTO");
        Long judge = assignReferee(tid, stage.getId(), "裁判A");
        insertPending(tid, stage.getId(), "选手1", "1", 1);
        insertPending(tid, stage.getId(), "选手2", "2", 2);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        submitTech(match.getId(), judge, realParticipants(match.getId()), 80);

        TMatch stored = matchMapper.selectById(match.getId());
        assertNull(stored.getResultJson(), "AUTO 不应写入公布标记");
        assertEquals(StageConstants.MATCH_GAMING, stored.getStatus());
        assertEquals(StageConstants.STAGE_GAMING, stageService.queryById(stage.getId()).getStatus());
        assertFalse(Boolean.TRUE.equals(directorMatch(stage.getId(), match.getId()).getPendingPublish()));

        TMatchVo publicVo = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertEquals(0, new BigDecimal("80").compareTo(publicVo.getRoundScores().get(0).getScore()),
            "AUTO 公共端应实时露分");
        assertNotNull(lifecycleService.getRankDetail(stage.getId())
            .getCircles().get(0).getCompetitors().get(0).getScoreValue());
    }

    /** BATCH:全部打完自动完成赛段并一次性公布;打完前公共端隐藏分数但标记已打。 */
    @Test
    void batchRankAutoCompletesAndHidesUntilSettled() {
        Long tid = newTournament("排名赛批量公布");
        var stage = newRankStage(tid, "排名批量", 2, 1, "BATCH");
        Long judge = assignReferee(tid, stage.getId(), "裁判A");
        insertPending(tid, stage.getId(), "选手1", "1", 1);
        insertPending(tid, stage.getId(), "选手2", "2", 2);
        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        List<TMatchParticipant> parts = realParticipants(match.getId());

        // 只打一人:尚未齐,公共端隐藏已打格子
        SubmitResultBo partial = new SubmitResultBo();
        partial.setMatchId(match.getId());
        partial.setRefereeId(judge);
        ScoreEntryBo onlyFirst = new ScoreEntryBo();
        onlyFirst.setCompetitorId(parts.get(0).getCompetitorId());
        onlyFirst.setDimension("TECH");
        onlyFirst.setScore(BigDecimal.valueOf(70));
        partial.setScores(List.of(onlyFirst));
        matchResultService.submitResult(partial);

        assertEquals(StageConstants.STAGE_GAMING, stageService.queryById(stage.getId()).getStatus(),
            "未打齐时 BATCH 不应完成赛段");
        TMatchVo mid = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertNull(mid.getRoundScores().get(0).getScore());
        assertTrue(Boolean.TRUE.equals(
            mid.getRoundScores().get(0).getDimensions().get(0).getRefereeScores().get(0).getScored()));

        submitTech(match.getId(), judge, parts, 70);
        assertEquals(StageConstants.STAGE_SETTLED, stageService.queryById(stage.getId()).getStatus(),
            "BATCH 全部打完应自动完成赛段");
        TMatchVo done = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertNotNull(done.getRoundScores().get(0).getScore(), "赛段结算后公共端应露分");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
    }

    /** 淘汰赛 MANUAL:公布前公共接口隐藏真实判罚,只保留已判标记;导播台仍看真实结果;公布后露分。 */
    @Test
    void knockoutManualMasksVotesOnPublicApi() throws Exception {
        Long tid = newTournament("淘汰赛手动公布遮罩");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("决赛");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(2L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\",\"knockout\":{\"teamsCount\":2,"
            + "\"advanceCount\":1,\"pairingMode\":\"SEED\",\"publishMode\":\"MANUAL\"},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        var stage = stageService.insertByBo(bo);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long judge = assignReferee(tid, stage.getId(), "裁判1");

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        voteLeft(match.getId(), judge);

        TMatchVo director = directorMatch(stage.getId(), match.getId());
        assertTrue(Boolean.TRUE.equals(director.getPendingPublish()));
        assertNotNull(director.getRoundVotes());
        String realVote = director.getRoundVotes().get(0).getRefereeVotes().get(0).getVote();
        assertNotNull(realVote, "导播台应看到真实判罚");
        assertNotNull(director.getRoundVotes().get(0).getWinnerSide(), "导播台应看到本轮胜方");

        TMatchVo publicVo = copyAndMask(director);
        assertNull(publicVo.getResultJson());
        assertNull(publicVo.getWinnerName());
        assertNull(publicVo.getLeftWin());
        assertNull(publicVo.getRightWin());
        assertNull(publicVo.getRoundVotes().get(0).getWinnerSide());
        var pubVote = publicVo.getRoundVotes().get(0).getRefereeVotes().get(0);
        assertTrue(Boolean.TRUE.equals(pubVote.getVoted()), "应标记该裁判已判");
        assertNull(pubVote.getVote(), "公布前不得露出 LEFT/RIGHT/DRAW");

        mvc().perform(get("/tournament/screen/match/" + match.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultJson").value(nullValue()))
            .andExpect(jsonPath("$.data.roundVotes[0].winnerSide").value(nullValue()))
            .andExpect(jsonPath("$.data.roundVotes[0].refereeVotes[0].vote").value(nullValue()))
            .andExpect(jsonPath("$.data.roundVotes[0].refereeVotes[0].voted").value(true));

        matchResultService.publishResult(match.getId());
        TMatchVo after = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertEquals(StageConstants.MATCH_SETTLED, after.getStatus());
        assertEquals(realVote, after.getRoundVotes().get(0).getRefereeVotes().get(0).getVote(),
            "公布后公共端应露出真实判罚");
        assertNotNull(after.getRoundVotes().get(0).getWinnerSide());
    }

    /** 排名赛未待公布时点公布应被拒绝,避免误走淘汰赛结算。 */
    @Test
    void publishRankWithoutPendingIsRejected() {
        Long tid = newTournament("排名赛无待公布");
        var stage = newRankStage(tid, "排名未齐", 2, 1, "MANUAL");
        assignReferee(tid, stage.getId(), "裁判A");
        insertPending(tid, stage.getId(), "选手1", "1", 1);
        insertPending(tid, stage.getId(), "选手2", "2", 2);
        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> matchResultService.publishResult(match.getId()));
        assertTrue(ex.getMessage().contains("暂无待公布结果"), ex.getMessage());
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus());
    }


    /** 公布前:评分详情公共接口只给目标 ID,当前场次 flow 与场次详情不露真实分,只保留已打勾;导播/裁判仍可见真分。 */
    @Test
    void publicWidgetAndCurrentMatchHideScoresUntilPublish() throws Exception {
        Long tid = newTournament("排名赛公布遮罩-评分详情");
        var stage = newRankStage(tid, "排名手动", 2, 1, "MANUAL");
        Long judge = assignReferee(tid, stage.getId(), "裁判A", "ref-rank-mask");
        insertPending(tid, stage.getId(), "选手1", "1", 1);
        insertPending(tid, stage.getId(), "选手2", "2", 2);
        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        List<TMatchParticipant> parts = realParticipants(match.getId());
        submitTech(match.getId(), judge, parts, 90);

        Long widgetId = newScoreDetailWidget(tid, "评分详情");
        widgetScoreDetailService.setTarget(tid, widgetId, match.getId(), parts.get(0).getCompetitorId());

        // 导播台仍看真分
        TMatchVo director = directorMatch(stage.getId(), match.getId());
        assertEquals(0, new BigDecimal("90").compareTo(director.getRoundScores().get(0).getScore()));

        // 裁判端仍看自己的打分
        RefereeMatchVo referee = refereeMatchService.myMatch(tid, judge, "裁判A", stage.getId(), match.getId());
        assertNotNull(referee.getMyScores());
        assertFalse(referee.getMyScores().isEmpty(), "裁判应能看到自己已提交的分数");
        assertTrue(referee.getMyScores().stream().anyMatch(s -> s.getScore() != null
            && s.getScore().compareTo(new BigDecimal("90")) == 0), "裁判明细应含真实分数");
        assertTrue(referee.getParticipants().stream().anyMatch(p -> p.getMyScore() != null
            || p.getCurrentScore() != null), "裁判端参赛方应带真分");

        // 评分详情公共接口只返回目标,不含分数字段
        mvc().perform(get("/tournament/screen/widget/" + widgetId + "/score-detail"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.matchId").value(match.getId()))
            .andExpect(jsonPath("$.data.competitorId").value(parts.get(0).getCompetitorId()))
            .andExpect(jsonPath("$.data.score").doesNotExist());

        // 该组件实际用来展示分数的场次公共详情:已打只出 scored,不露分
        mvc().perform(get("/tournament/screen/match/" + match.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roundScores[0].score").value(nullValue()))
            .andExpect(jsonPath("$.data.roundScores[0].dimensions[0].refereeScores[0].scored").value(true))
            .andExpect(jsonPath("$.data.roundScores[0].dimensions[0].refereeScores[0].score").value(nullValue()));

        // 当前场次控件:公布前不露总分/名次
        mvc().perform(get("/tournament/screen/flow/" + tid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentMatch.id").value(match.getId()))
            .andExpect(jsonPath("$.data.currentMatchParticipants[0].scoreValue").value(nullValue()))
            .andExpect(jsonPath("$.data.currentMatchParticipants[0].rankInMatch").value(nullValue()));

        matchResultService.publishResult(match.getId());
        TMatchVo publicAfter = copyAndMask(directorMatch(stage.getId(), match.getId()));
        assertEquals(0, new BigDecimal("90").compareTo(publicAfter.getRoundScores().get(0).getScore()));
        mvc().perform(get("/tournament/screen/flow/" + tid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentMatchParticipants[0].scoreValue").value(org.hamcrest.Matchers.not(nullValue())));
    }

    /** 淘汰赛 MANUAL:公布前当前场次公共接口隐藏胜负;裁判端仍能看到自己的判罚。 */
    @Test
    void knockoutCurrentMatchAndRefereeRespectManualPublish() throws Exception {
        Long tid = newTournament("淘汰赛当前场次遮罩");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("决赛");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(2L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\",\"knockout\":{\"teamsCount\":2,"
            + "\"advanceCount\":1,\"pairingMode\":\"SEED\",\"publishMode\":\"MANUAL\"},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        var stage = stageService.insertByBo(bo);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long judge = assignReferee(tid, stage.getId(), "裁判1", "ref-ko-mask");

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        voteLeft(match.getId(), judge);

        TMatchVo director = directorMatch(stage.getId(), match.getId());
        assertNotNull(director.getRoundVotes().get(0).getRefereeVotes().get(0).getVote(),
            "导播台应看到真实判罚");

        RefereeMatchVo referee = refereeMatchService.myMatch(tid, judge, "裁判1", stage.getId(), match.getId());
        assertTrue(Boolean.TRUE.equals(referee.getPendingPublish()));
        assertNotNull(referee.getMyScores());
        assertFalse(referee.getMyScores().isEmpty(), "裁判应能看到自己已提交的判罚");
        assertTrue(referee.getMyScores().stream().anyMatch(s -> s.getScore() != null),
            "裁判端不得把已判结果遮掉");

        mvc().perform(get("/tournament/screen/match/" + match.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roundVotes[0].winnerSide").value(nullValue()))
            .andExpect(jsonPath("$.data.roundVotes[0].refereeVotes[0].vote").value(nullValue()))
            .andExpect(jsonPath("$.data.roundVotes[0].refereeVotes[0].voted").value(true));

        mvc().perform(get("/tournament/screen/flow/" + tid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentMatch.id").value(match.getId()))
            .andExpect(jsonPath("$.data.currentMatchParticipants[0].outcomeStatus").value(nullValue()));
    }


    private TMatchVo directorMatch(Long stageId, Long matchId) {
        TMatchBo q = new TMatchBo();
        q.setStageId(stageId);
        return matchService.queryList(q).stream()
            .filter(m -> m.getId().equals(matchId))
            .findFirst().orElseThrow();
    }

    private TMatchVo copyAndMask(TMatchVo src) {
        // 遮罩会改 VO,先重新查一份再遮,避免污染导播台视图断言
        TMatchVo fresh = directorMatch(src.getStageId(), src.getId());
        screenResultMasker.maskMatch(fresh);
        return fresh;
    }

    private void submitTech(Long matchId, Long refereeId, List<TMatchParticipant> parts, int base) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setDimension("TECH");
            se.setScore(BigDecimal.valueOf(base - i * 10));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    private void voteLeft(Long matchId, Long refereeId) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(parts.get(0).getCompetitorId(), "WIN");
        outcomes.put(parts.get(1).getCompetitorId(), "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private com.dance.street.game.domain.vo.TStageVo newRankStage(Long tid, String name,
                                                                 int start, int end, String publishMode) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("RANK");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) start);
        bo.setTeamCountEnd((long) end);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"RANK\",\"circles\":1,\"advanceCount\":" + end
            + ",\"publishMode\":\"" + publishMode + "\",\"maxScore\":100,"
            + "\"scoring\":{\"type\":\"MULTI_DIM\",\"matchMode\":\"RANKING\","
            + "\"refereeAggregateRule\":\"AVG\",\"dimensions\":["
            + "{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":100,\"maxScore\":100}]}}");
        return stageService.insertByBo(bo);
    }

    private Long assignReferee(Long tid, Long stageId, String name) {
        return assignReferee(tid, stageId, name, null);
    }

    private Long assignReferee(Long tid, Long stageId, String name, String authKey) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        r.setAuthKey(authKey);
        refereeMapper.insert(r);
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stageId);
        bo.setTournamentId(tid);
        bo.setRefereeIds(List.of(r.getId()));
        refereeStageService.assignReferees(bo);
        return r.getId();
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
    private Long newScoreDetailWidget(Long tid, String name) {
        TVisScene scene = new TVisScene();
        scene.setTournamentId(tid);
        scene.setName("主场景");
        scene.setDesignWidth(1920L);
        scene.setDesignHeight(1080L);
        visSceneMapper.insert(scene);
        TVisWidget widget = new TVisWidget();
        widget.setTournamentId(tid);
        widget.setSceneId(scene.getId());
        widget.setName(name);
        widget.setType(WidgetScoreDetailStore.WIDGET_TYPE);
        widget.setLayoutConfig("{}");
        widget.setRenderConfig("{}");
        widget.setX(0L);
        widget.setY(0L);
        widget.setW(800L);
        widget.setH(400L);
        widget.setZIndex(1L);
        widget.setVisible(1L);
        widget.setLocked(0L);
        visWidgetMapper.insert(widget);
        return widget.getId();
    }

}
