package com.dance.street.game.service;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * 选手照片/姓名变更的 SSE 广播回归。
 *
 * <p>事故现象:在选手管理里给选手补传照片后,大屏/导播台必须手动刷新才更新。
 * 两个原因:</p>
 * <ol>
 *   <li>{@code t_player} 的更新只在「改名联动到参赛单位」时才广播,单纯换头像没有任何事件;</li>
 *   <li>大屏组件是按事件的 stageId 过滤刷新的,广播必须带上选手所在赛段的 id ——
 *       参赛单位改名时调用方(赛段选手列表)只传 id+name,赛事/赛段为空会被通知器静默丢弃。</li>
 * </ol>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class PlayerProfileSseNotifyTest {

    private static final String DB_PATH = "target/player-profile-sse.db";

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

    /**
     * 记录广播事件的通知器替身(与 SseSendDispatcherTest 用子类观测同理,项目里不用 Mockito)。
     * 断言口径:有没有广播、广播带没带 stageId —— 大屏只认 stageId 匹配的事件。
     */
    static class RecordingNotifier extends TournamentEventNotifier {

        final List<String> events = new CopyOnWriteArrayList<>();

        RecordingNotifier(TournamentEventSseEmitterManager manager) {
            super(manager);
        }

        @Override
        public void notify(Long tournamentId, Long stageId, Long matchId, String type) {
            events.add(tournamentId + "/" + stageId + "/" + type);
            super.notify(tournamentId, stageId, matchId, type);
        }
    }

    @TestConfiguration
    static class RecorderConfig {

        @Bean
        @Primary
        RecordingNotifier recordingNotifier(TournamentEventSseEmitterManager manager) {
            return new RecordingNotifier(manager);
        }
    }

    @Autowired
    private RecordingNotifier notifier;
    @Autowired
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TCompetitorMemberMapper competitorMemberMapper;
    @Autowired
    private TPlayerMapper playerMapper;
    @Autowired
    private ITPlayerService playerService;
    @Autowired
    private ITCompetitorService competitorService;

    @BeforeEach
    void clearEvents() {
        notifier.events.clear();
    }

    @Test
    void playerAvatarChangeNotifiesTheStage() {
        Fixture f = seed();

        TPlayerBo bo = new TPlayerBo();
        bo.setId(f.playerId());
        bo.setAvatar("/upload/avatar-a.png");
        playerService.updateByBo(bo);

        assertTrue(notifier.events.contains(f.event("competitor")),
            "换照片应广播带 stageId 的 competitor 事件,实际: " + notifier.events);
    }

    @Test
    void playerNameChangeNotifiesTheStage() {
        Fixture f = seed();

        TPlayerBo bo = new TPlayerBo();
        bo.setId(f.playerId());
        bo.setName("选手A改名");
        playerService.updateByBo(bo);

        assertTrue(notifier.events.contains(f.event("competitor")),
            "改姓名应广播带 stageId 的 competitor 事件,实际: " + notifier.events);
    }

    @Test
    void untouchedProfileDoesNotNotify() {
        Fixture f = seed();

        TPlayerBo bo = new TPlayerBo();
        bo.setId(f.playerId());
        bo.setRemark("只改备注");
        playerService.updateByBo(bo);

        assertTrue(notifier.events.isEmpty(), "没改姓名/照片不该广播,实际: " + notifier.events);
    }

    @Test
    void competitorRenameNotifiesEvenIfBoOmitsTournamentAndStage() {
        Fixture f = seed();

        // 赛段选手列表的改名就是这种最小 BO:只有 id+name,赛事/赛段靠库里反查
        TCompetitorBo bo = new TCompetitorBo();
        bo.setId(f.competitorId());
        bo.setName("选手A战队");
        bo.setSyncPlayerName(false);
        competitorService.updateByBo(bo);

        assertTrue(notifier.events.contains(f.event("competitor")),
            "改参赛单位名应广播带 stageId 的 competitor 事件,实际: " + notifier.events);
    }

    private Fixture seed() {
        TTournament tournament = new TTournament();
        tournament.setName("资料变更广播赛事");
        tournamentMapper.insert(tournament);

        TStage stage = new TStage();
        stage.setTournamentId(tournament.getId());
        stage.setName("16强");
        stage.setStageMode(StageModeEnum.KNOCKOUT.getCode());
        stage.setStatus(StageConstants.STAGE_DRAFT);
        stageMapper.insert(stage);

        TPlayer player = new TPlayer();
        player.setTournamentId(tournament.getId());
        player.setName("选手A");
        playerMapper.insert(player);

        TCompetitor competitor = new TCompetitor();
        competitor.setTournamentId(tournament.getId());
        competitor.setStageId(stage.getId());
        competitor.setName("选手A");
        competitorMapper.insert(competitor);

        TCompetitorMember member = new TCompetitorMember();
        member.setTournamentId(tournament.getId());
        member.setCompetitorId(competitor.getId());
        member.setPlayerId(player.getId());
        member.setRole("MEMBER");
        competitorMemberMapper.insert(member);

        return new Fixture(tournament.getId(), stage.getId(), competitor.getId(), player.getId());
    }

    private record Fixture(Long tournamentId, Long stageId, Long competitorId, Long playerId) {

        String event(String type) {
            return tournamentId + "/" + stageId + "/" + type;
        }
    }
}
