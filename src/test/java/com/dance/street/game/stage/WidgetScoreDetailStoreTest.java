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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「评分详情」组件的推送目标走跨实例缓存(Redis),按 widgetId 存,不落库。
 *
 * <p>测试环境 Redis 是关的,跑的是 RedisUtils 降级路径;这里断言值能从
 * {@link WidgetScoreDetailStore#KEY_PREFIX} 这个共享缓存键读回来,
 * 并且 set/get/clear 与归属校验按约定工作。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class WidgetScoreDetailStoreTest {

    private static final String DB_PATH = "target/widget-score-detail.db";

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
    private TVisSceneMapper sceneMapper;
    @Autowired
    private TVisWidgetMapper widgetMapper;
    @Autowired
    private WidgetScoreDetailService scoreDetailService;

    @Test
    void markGoesThroughSharedCacheAndValidates() {
        TTournament t = new TTournament();
        t.setName("评分详情推送");
        tournamentMapper.insert(t);

        TStage stage = new TStage();
        stage.setTournamentId(t.getId());
        stage.setName("排名赛");
        stage.setStageMode("RANK");
        stage.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.insert(stage);

        TMatch match = new TMatch();
        match.setTournamentId(t.getId());
        match.setStageId(stage.getId());
        match.setName("第一圈");
        match.setStatus(StageConstants.MATCH_GAMING);
        match.setMatchMode("VOTING");
        matchMapper.insert(match);

        TCompetitor c = new TCompetitor();
        c.setTournamentId(t.getId());
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName("选手A");
        c.setNumber("1");
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);

        TCompetitor other = new TCompetitor();
        other.setTournamentId(t.getId());
        other.setStageId(stage.getId());
        other.setType(0L);
        other.setName("选手B");
        other.setNumber("2");
        other.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(other);

        TMatchParticipant participant = new TMatchParticipant();
        participant.setTournamentId(t.getId());
        participant.setMatchId(match.getId());
        participant.setCompetitorId(c.getId());
        participant.setDisplaySlotIndex(0L);
        participant.setSlotKind(StageConstants.SLOT_PLAYER);
        participant.setOutcomeStatus("PENDING");
        participantMapper.insert(participant);

        TVisScene scene = new TVisScene();
        scene.setTournamentId(t.getId());
        scene.setName("主场景");
        scene.setDesignWidth(1920L);
        scene.setDesignHeight(1080L);
        sceneMapper.insert(scene);

        TVisWidget widget = new TVisWidget();
        widget.setTournamentId(t.getId());
        widget.setSceneId(scene.getId());
        widget.setName("评分详情 1");
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

        scoreDetailService.setTarget(t.getId(), widget.getId(), match.getId(), c.getId());

        String raw = RedisUtils.getCacheObject(WidgetScoreDetailStore.KEY_PREFIX + widget.getId());
        assertNotNull(raw, "推送目标应写入跨实例缓存");
        assertTrue(raw.contains(String.valueOf(match.getId())), "缓存 JSON 应包含 matchId");
        assertTrue(raw.contains(String.valueOf(c.getId())), "缓存 JSON 应包含 competitorId");

        WidgetScoreDetailTargetVo target = scoreDetailService.getTarget(t.getId(), widget.getId());
        assertNotNull(target);
        assertEquals(match.getId(), target.getMatchId());
        assertEquals(c.getId(), target.getCompetitorId());

        WidgetScoreDetailTargetVo pub = scoreDetailService.getTargetPublic(widget.getId());
        assertNotNull(pub);
        assertEquals(c.getId(), pub.getCompetitorId());

        List<ScoreDetailWidgetVo> listed = scoreDetailService.listForTournament(t.getId());
        assertEquals(1, listed.size(), "列表只应包含评分详情组件");
        assertEquals(widget.getId(), listed.get(0).getId());
        assertEquals("主场景", listed.get(0).getSceneName());
        assertEquals(match.getId(), listed.get(0).getMatchId());
        assertEquals(c.getId(), listed.get(0).getCompetitorId());

        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(t.getId(), textWidget.getId(), match.getId(), c.getId()),
            "非评分详情组件不能作为推送目标");
        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(t.getId(), widget.getId(), match.getId(), other.getId()),
            "不属于该场次的选手不能推送");
        assertThrows(ServiceException.class,
            () -> scoreDetailService.setTarget(t.getId(), widget.getId(), match.getId(), null),
            "只传场次不传选手应视为参数不完整");

        scoreDetailService.setTarget(t.getId(), widget.getId(), null, null);
        assertNull(RedisUtils.getCacheObject(WidgetScoreDetailStore.KEY_PREFIX + widget.getId()),
            "清除推送应删除缓存键");
        assertNull(scoreDetailService.getTarget(t.getId(), widget.getId()));
        assertNull(scoreDetailService.getTargetPublic(widget.getId()));
    }
}
