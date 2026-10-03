package com.dance.street.game.stage;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.vo.StageCircleLabelsVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.impl.flow.CircleLabelService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圈标签只读接口:一次批量返回"参赛方 → A圈/B圈…",替代前端 5 个串行请求;
 * 非海选赛段只回模式/状态、标签为空。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class CircleLabelServiceTest {

    private static final String DB_PATH = "target/circle-label-service.db";

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
    @Autowired private CircleLabelService circleLabelService;

    @Test
    void auditionCirclesLabeledByLetter() {
        Long tid = newTournament("圈标签");
        Long stageId = newStage(tid, "海选", "AUDITION");
        Long zone1 = newZone(tid, stageId, 1, 0);
        Long zone2 = newZone(tid, stageId, 2, 1);
        addParticipant(tid, zone1, 101L, 0);
        addParticipant(tid, zone2, 202L, 0);
        addParticipant(tid, zone2, 303L, 1);

        StageCircleLabelsVo vo = circleLabelService.labels(stageId);
        assertEquals("AUDITION", vo.getStageMode());
        assertEquals(2, vo.getCircleCount());
        assertEquals("A圈", vo.getLabels().get("101"));
        assertEquals("B圈", vo.getLabels().get("202"));
        assertEquals("B圈", vo.getLabels().get("303"));
    }

    @Test
    void nonAuditionReturnsModeOnly() {
        Long tid = newTournament("非海选");
        Long stageId = newStage(tid, "排名", "RANK");

        StageCircleLabelsVo vo = circleLabelService.labels(stageId);
        assertEquals("RANK", vo.getStageMode());
        assertEquals(0, vo.getCircleCount());
        assertTrue(vo.getLabels().isEmpty());
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private Long newStage(Long tid, String name, String mode) {
        TStage s = new TStage();
        s.setTournamentId(tid);
        s.setName(name);
        s.setStageMode(mode);
        s.setStatus(StageConstants.STAGE_DRAFT);
        s.setTeamCountStart(8L);
        s.setTeamCountEnd(4L);
        s.setIsInitialized(0L);
        stageMapper.insert(s);
        return s.getId();
    }

    private Long newZone(Long tid, Long stageId, int zoneNo, int row) {
        TMatch m = new TMatch();
        m.setTournamentId(tid);
        m.setStageId(stageId);
        m.setName("ZONE-" + zoneNo);
        m.setDisplayZone("ZONE-" + zoneNo);
        m.setDisplayRow((long) row);
        m.setDisplayCol(1L);
        m.setStatus(StageConstants.MATCH_PENDING);
        m.setMatchMode("VOTING");
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);
        return m.getId();
    }

    private void addParticipant(Long tid, Long matchId, Long competitorId, int slot) {
        TMatchParticipant p = new TMatchParticipant();
        p.setTournamentId(tid);
        p.setMatchId(matchId);
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex((long) slot);
        p.setSlotKind(StageConstants.SLOT_PLAYER);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
    }
}
