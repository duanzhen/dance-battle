package com.dance.street.game.service;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段「判罚方式」切换要通知裁判端。
 *
 * <p>事故现象:把淘汰赛/擂台赛从「裁判判罚」改成「导播台判定」后,已经打开的裁判页还停在
 * 原来的判罚界面上(反之从导播台改回裁判判罚也一样),现场得手动刷新。原因是赛段配置保存
 * 只写库、不推裁判通道,而裁判页只在收到推送时才重新拉数据。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageConfigSseNotifyTest {

    private static final String DB_PATH = "target/stage-config-sse.db";

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

    /** 记录裁判通道推送的通知器替身(与 PlayerProfileSseNotifyTest 同一手法)。 */
    static class RecordingRefereeNotifier extends RefereeSseNotifier {

        final List<String> events = new CopyOnWriteArrayList<>();

        RecordingRefereeNotifier(ITRefereeStageService refereeStageService,
                                 TournamentEventSseEmitterManager tournamentEventSseEmitterManager,
                                 TStageMapper stageMapper,
                                 TMatchMapper matchMapper,
                                 TMatchRefereeMapper matchRefereeMapper) {
            super(refereeStageService, tournamentEventSseEmitterManager, stageMapper, matchMapper, matchRefereeMapper);
        }

        @Override
        public void notifyStage(Long stageId, String type) {
            events.add(stageId + "/-/ " + type);
            super.notifyStage(stageId, type);
        }

        @Override
        public void notifyMatch(Long stageId, Long matchId, String type) {
            events.add(stageId + "/" + matchId + "/ " + type);
            super.notifyMatch(stageId, matchId, type);
        }
    }

    @TestConfiguration
    static class RefereeNotifierRecorderConfig {

        @Bean
        @Primary
        RecordingRefereeNotifier recordingRefereeNotifier(ITRefereeStageService refereeStageService,
                                                          TournamentEventSseEmitterManager manager,
                                                          TStageMapper stageMapper,
                                                          TMatchMapper matchMapper,
                                                          TMatchRefereeMapper matchRefereeMapper) {
            return new RecordingRefereeNotifier(refereeStageService, manager, stageMapper, matchMapper, matchRefereeMapper);
        }
    }

    @Autowired
    private RecordingRefereeNotifier refereeNotifier;
    @Autowired
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private ITStageService stageService;

    /** 淘汰赛:knockout.publishMode 在 裁判判罚(AUTO) 与 导播台判定(DIRECTOR) 之间来回切都要推。 */
    @Test
    void knockoutPublishModeSwitchNotifiesReferee() {
        Long tid = newTournament("淘汰赛判罚方式切换");
        TStageVo stage = newStage(tid, "8强", "KNOCKOUT");

        refereeNotifier.events.clear();
        savePublishMode(stage, "DIRECTOR");
        assertTrue(refereeNotifier.events.contains(stage.getId() + "/-/ config"),
            "改成导播台判定应推裁判端,实际: " + refereeNotifier.events);

        refereeNotifier.events.clear();
        savePublishMode(stage, "AUTO");
        assertTrue(refereeNotifier.events.contains(stage.getId() + "/-/ config"),
            "改回裁判判罚也要推裁判端,实际: " + refereeNotifier.events);
    }

    /** 擂台赛:判罚方式写在 ruleConfig 顶层,同样要推。 */
    @Test
    void arenaPublishModeSwitchNotifiesReferee() {
        Long tid = newTournament("擂台判罚方式切换");
        TStageVo stage = newStage(tid, "擂台赛", "ARENA");

        refereeNotifier.events.clear();
        savePublishMode(stage, "DIRECTOR");
        assertTrue(refereeNotifier.events.contains(stage.getId() + "/-/ config"),
            "擂台赛改成导播台判定应推裁判端,实际: " + refereeNotifier.events);
    }

    /** 判罚方式没变(纯保存一次)不该推,避免每次保存配置都刷一遍裁判端。 */
    @Test
    void unchangedConfigDoesNotNotify() {
        Long tid = newTournament("配置未变化");
        TStageVo stage = newStage(tid, "8强", "KNOCKOUT");

        refereeNotifier.events.clear();
        savePublishMode(stage, "AUTO");
        assertFalse(refereeNotifier.events.contains(stage.getId() + "/-/ config"),
            "判罚方式没变不该推,实际: " + refereeNotifier.events);
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name, String mode) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(8L);
        bo.setTeamCountEnd("ARENA".equals(mode) ? 1L : 4L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("ARENA".equals(mode)
            ? "{\"mode\":\"ARENA\",\"scale\":8,\"format\":\"BO1\",\"publishMode\":\"AUTO\"}"
            : "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":8,\"advanceCount\":4,\"publishMode\":\"AUTO\"}}");
        return stageService.insertByBo(bo);
    }

    /** 按赛段模式把 publishMode 写到正确的位置(淘汰赛=knockout 段,擂台赛=顶层)。 */
    private void savePublishMode(TStageVo stage, String publishMode) {
        String rule = "ARENA".equals(stage.getStageMode())
            ? "{\"mode\":\"ARENA\",\"scale\":8,\"format\":\"BO1\",\"publishMode\":\"" + publishMode + "\"}"
            : "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":8,\"advanceCount\":4,\"publishMode\":\""
                + publishMode + "\"}}";
        TStageConfigBo bo = new TStageConfigBo();
        bo.setId(stage.getId());
        bo.setRuleConfig(rule);
        stageService.updateConfig(bo);
    }
}
