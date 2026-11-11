package com.dance.street.game.stage;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.vo.ScoreDetailWidgetVo;
import com.dance.street.game.domain.vo.WidgetScoreDetailTargetVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.impl.WidgetScoreDetailService;
import com.dance.street.game.service.impl.WidgetScoreDetailStore;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「评分详情」组件的推送目标走跨实例缓存(Redis),按 widgetId 存,不落库。
 *
 * <p>测试环境 Redis 是关的,跑的是 RedisUtils 降级路径;这里断言值能从
 * {@link WidgetScoreDetailStore#KEY_PREFIX} 这个共享缓存键读回来,
 * 并且 set/get/clear、按 widget 隔离、再点同一选手清空,以及导播/公共读写接口按约定工作。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class WidgetScoreDetailStoreTest {

    private static final String DB_PATH = "target/widget-score-detail.db";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

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
    private WebApplicationContext wac;
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
    private TVisSceneMapper sceneMapper;
    @Autowired
    private TVisWidgetMapper widgetMapper;
    @Autowired
    private WidgetScoreDetailService scoreDetailService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void markGoesThroughSharedCacheAndValidates() {
        Fixture fx = newFixture("评分详情推送", null);

        scoreDetailService.setTarget(fx.tid, fx.widgetId, fx.matchId, fx.competitorA);

        String raw = RedisUtils.getCacheObject(WidgetScoreDetailStore.KEY_PREFIX + fx.widgetId);
        assertNotNull(raw, "推送目标应写入跨实例缓存");
        assertTrue(raw.contains(String.valueOf(fx.matchId)), "缓存 JSON 应包含 matchId");
        assertTrue(raw.contains(String.valueOf(fx.competitorA)), "缓存 JSON 应包含 competitorId");

        WidgetScoreDetailTargetVo target = scoreDetailService.getTarget(fx.tid, fx.widgetId);
        assertNotNull(target);
        assertEquals(fx.matchId, target.getMatchId());
        assertEquals(fx.competitorA, target.getCompetitorId());

        WidgetScoreDetailTargetVo pub = scoreDetailService.getTargetPublic(fx.widgetId);
        assertNotNull(pub);
        assertEquals(fx.competitorA, pub.getCompetitorId());

        List<ScoreDetailWidgetVo> listed = scoreDetailService.listForTournament(fx.tid);
        assertEquals(1, listed.size(), "列表只应包含评分详情组件");
        assertEquals(fx.widgetId, listed.get(0).getId());
        assertEquals("主场景", listed.get(0).getSceneName());
        assertEquals(fx.matchId, listed.get(0).getMatchId());
        assertEquals(fx.competitorA, listed.get(0).getCompetitorId());

        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(fx.tid, fx.textWidgetId, fx.matchId, fx.competitorA),
            "非评分详情组件不能作为推送目标");
        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(fx.tid, fx.widgetId, fx.matchId, fx.competitorB),
            "不属于该场次的选手不能推送");
        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(fx.tid, fx.widgetId, fx.matchId, null),
            "只传场次不传选手应视为参数不完整");

        scoreDetailService.setTarget(fx.tid, fx.widgetId, null, null);
        assertNull(RedisUtils.getCacheObject(WidgetScoreDetailStore.KEY_PREFIX + fx.widgetId),
            "清除推送应删除缓存键");
        assertNull(scoreDetailService.getTarget(fx.tid, fx.widgetId));
        assertNull(scoreDetailService.getTargetPublic(fx.widgetId));
    }

    @Test
    void bindingsAreIsolatedByWidgetId() {
        Fixture fx = newFixture("按组件隔离", null);
        TVisWidget second = newScoreDetailWidget(fx.tid, fx.sceneId, "评分详情 2");

        scoreDetailService.setTarget(fx.tid, fx.widgetId, fx.matchId, fx.competitorA);
        scoreDetailService.setTarget(fx.tid, second.getId(), fx.secondMatchId, fx.competitorOnSecond);

        WidgetScoreDetailTargetVo first = scoreDetailService.getTarget(fx.tid, fx.widgetId);
        WidgetScoreDetailTargetVo other = scoreDetailService.getTarget(fx.tid, second.getId());
        assertEquals(fx.competitorA, first.getCompetitorId());
        assertEquals(fx.competitorOnSecond, other.getCompetitorId());
        assertEquals(fx.matchId, first.getMatchId());
        assertEquals(fx.secondMatchId, other.getMatchId());

        scoreDetailService.setTarget(fx.tid, fx.widgetId, null, null);
        assertNull(scoreDetailService.getTarget(fx.tid, fx.widgetId));
        assertEquals(fx.competitorOnSecond, scoreDetailService.getTarget(fx.tid, second.getId()).getCompetitorId(),
            "清掉一个组件不应影响另一个 widgetId 的绑定");
    }

    @Test
    void markingTheSameCompetitorAgainClears() {
        Fixture fx = newFixture("再点同一选手清空", null);
        scoreDetailService.setTarget(fx.tid, fx.widgetId, fx.matchId, fx.competitorA);
        assertEquals(fx.competitorA, scoreDetailService.getTarget(fx.tid, fx.widgetId).getCompetitorId());

        // 与导播台「再点同一选手则清除」一致:读到当前目标相同后走清空,而不是再写一遍
        WidgetScoreDetailTargetVo current = scoreDetailService.getTarget(fx.tid, fx.widgetId);
        if (current != null
            && fx.matchId.equals(current.getMatchId())
            && fx.competitorA.equals(current.getCompetitorId())) {
            scoreDetailService.setTarget(fx.tid, fx.widgetId, null, null);
        }
        assertNull(scoreDetailService.getTarget(fx.tid, fx.widgetId));
        assertNull(RedisUtils.getCacheObject(WidgetScoreDetailStore.KEY_PREFIX + fx.widgetId));
    }

    @Test
    void directorAndPublicHttpApisReadAndWriteByWidgetId() throws Exception {
        String authKey = "score-detail-key-" + SEQ.incrementAndGet();
        Fixture fx = newFixture("评分详情读写接口", authKey);
        MockMvc mvc = mvc();

        mvc.perform(get("/game/director/score-detail-widgets")
                .header("Authorization", "Bearer " + authKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(fx.widgetId))
            .andExpect(jsonPath("$.data[0].matchId").value(nullValue()));

        mvc.perform(put("/game/director/widget/" + fx.widgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matchId\":" + fx.matchId + ",\"competitorId\":" + fx.competitorA + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get("/game/director/widget/" + fx.widgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.matchId").value(fx.matchId))
            .andExpect(jsonPath("$.data.competitorId").value(fx.competitorA));

        mvc.perform(get("/tournament/screen/widget/" + fx.widgetId + "/score-detail"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.matchId").value(fx.matchId))
            .andExpect(jsonPath("$.data.competitorId").value(fx.competitorA))
            .andExpect(jsonPath("$.data.score").doesNotExist());

        // 再点同一选手:前端会走清空(两边都传空)
        mvc.perform(put("/game/director/widget/" + fx.widgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matchId\":null,\"competitorId\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get("/game/director/widget/" + fx.widgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(get("/tournament/screen/widget/" + fx.widgetId + "/score-detail"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(nullValue()));

        // 校验:非评分详情组件、参数不完整
        mvc.perform(put("/game/director/widget/" + fx.textWidgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matchId\":" + fx.matchId + ",\"competitorId\":" + fx.competitorA + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
        mvc.perform(put("/game/director/widget/" + fx.widgetId + "/score-detail")
                .header("Authorization", "Bearer " + authKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matchId\":" + fx.matchId + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    private Fixture newFixture(String tournamentName, String authKey) {
        TTournament t = new TTournament();
        t.setName(tournamentName + "-" + SEQ.incrementAndGet());
        t.setAuthKey(authKey);
        tournamentMapper.insert(t);

        TStage stage = new TStage();
        stage.setTournamentId(t.getId());
        stage.setName("排名赛");
        stage.setStageMode("RANK");
        stage.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.insert(stage);

        TMatch match = newMatch(t.getId(), stage.getId(), "第一圈");
        TCompetitor a = newCompetitor(t.getId(), stage.getId(), "选手A", "1");
        TCompetitor other = newCompetitor(t.getId(), stage.getId(), "选手B", "2");
        attach(t.getId(), match.getId(), a.getId(), 0);
        // other 未进该场,用于校验「不属于该场次」

        TMatch second = newMatch(t.getId(), stage.getId(), "第二圈");
        TCompetitor onSecond = newCompetitor(t.getId(), stage.getId(), "选手C", "3");
        attach(t.getId(), second.getId(), onSecond.getId(), 0);

        TVisScene scene = new TVisScene();
        scene.setTournamentId(t.getId());
        scene.setName("主场景");
        scene.setDesignWidth(1920L);
        scene.setDesignHeight(1080L);
        sceneMapper.insert(scene);

        TVisWidget widget = newScoreDetailWidget(t.getId(), scene.getId(), "评分详情 1");

        TVisWidget textWidget = new TVisWidget();
        textWidget.setTournamentId(t.getId());
        textWidget.setSceneId(scene.getId());
        textWidget.setName("文本");
        textWidget.setType("TEXT");
        textWidget.setLayoutConfig("{}");
        textWidget.setRenderConfig("{}");
        textWidget.setX(0L);
        textWidget.setY(0L);
        textWidget.setW(100L);
        textWidget.setH(100L);
        textWidget.setZIndex(2L);
        textWidget.setVisible(1L);
        textWidget.setLocked(0L);
        widgetMapper.insert(textWidget);

        return new Fixture(t.getId(), match.getId(), second.getId(), a.getId(), other.getId(),
            onSecond.getId(), scene.getId(), widget.getId(), textWidget.getId());
    }

    private TMatch newMatch(Long tid, Long stageId, String name) {
        TMatch match = new TMatch();
        match.setTournamentId(tid);
        match.setStageId(stageId);
        match.setName(name);
        match.setStatus(StageConstants.MATCH_GAMING);
        match.setMatchMode("VOTING");
        matchMapper.insert(match);
        return match;
    }

    private TCompetitor newCompetitor(Long tid, Long stageId, String name, String number) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        return c;
    }

    private void attach(Long tid, Long matchId, Long competitorId, long slot) {
        TMatchParticipant participant = new TMatchParticipant();
        participant.setTournamentId(tid);
        participant.setMatchId(matchId);
        participant.setCompetitorId(competitorId);
        participant.setDisplaySlotIndex(slot);
        participant.setSlotKind(StageConstants.SLOT_PLAYER);
        participant.setOutcomeStatus("PENDING");
        participantMapper.insert(participant);
    }

    private TVisWidget newScoreDetailWidget(Long tid, Long sceneId, String name) {
        TVisWidget widget = new TVisWidget();
        widget.setTournamentId(tid);
        widget.setSceneId(sceneId);
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
        widget.setDataConfig("{\"showPlayerName\":true}");
        widgetMapper.insert(widget);
        return widget;
    }

    private record Fixture(Long tid, Long matchId, Long secondMatchId, Long competitorA, Long competitorB,
                           Long competitorOnSecond, Long sceneId, Long widgetId, Long textWidgetId) {
    }
}
