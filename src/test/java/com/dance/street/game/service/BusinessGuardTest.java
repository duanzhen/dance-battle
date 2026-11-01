package com.dance.street.game.service;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import com.dance.street.game.domain.bo.TRoundScoreBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class BusinessGuardTest {

    private static final String DB_PATH = "target/business-guard.db";

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
    @Autowired private TMatchRoundMapper roundMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRoundScoreMapper scoreMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITMatchService matchService;
    @Autowired private ITMatchParticipantService participantService;
    @Autowired private ITCompetitorService competitorService;
    @Autowired private ITRoundScoreService scoreService;
    @Autowired private ITStageLifecycleService lifecycleService;

    @Test
    void stageConfigCannotBypassStateMachineOrStartedConfigLock() {
        Long tournamentId = newTournament("赛段状态守卫");
        TStage stage = newStage(tournamentId);

        TStageConfigBo settle = new TStageConfigBo();
        settle.setId(stage.getId());
        settle.setStatus(StageConstants.STAGE_SETTLED);
        ServiceException statusError = assertThrows(ServiceException.class,
            () -> stageService.updateConfig(settle));
        assertTrue(statusError.getMessage().contains("不能直接标记为"),
            "状态迁移不能走配置入口: " + statusError.getMessage());
        assertEquals(StageConstants.STAGE_DRAFT, stageMapper.selectById(stage.getId()).getStatus());

        setStageStatus(stage.getId(), StageConstants.STAGE_GAMING);
        TStageConfigBo config = new TStageConfigBo();
        config.setId(stage.getId());
        config.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"format\":\"BO5\"}");
        ServiceException configError = assertThrows(ServiceException.class,
            () -> stageService.updateConfig(config));
        assertTrue(configError.getMessage().contains("已锁定"),
            "开赛后的赛制配置应锁定: " + configError.getMessage());
    }

    @Test
    void matchCrudCannotChangeStatusOrTouchStartedMatch() {
        Long tournamentId = newTournament("场次状态守卫");
        TStage stage = newStage(tournamentId);
        TMatch match = newMatch(tournamentId, stage.getId());

        TMatchBo statusPatch = new TMatchBo();
        statusPatch.setId(match.getId());
        statusPatch.setStatus(StageConstants.MATCH_GAMING);
        assertThrows(ServiceException.class, () -> matchService.updateByBo(statusPatch));
        assertEquals(StageConstants.MATCH_PENDING, matchMapper.selectById(match.getId()).getStatus());

        setStageStatus(stage.getId(), StageConstants.STAGE_GAMING);
        setMatchStatus(match.getId(), StageConstants.MATCH_GAMING);
        TMatchBo rename = new TMatchBo();
        rename.setId(match.getId());
        rename.setName("赛中被改");
        assertThrows(ServiceException.class, () -> matchService.updateByBo(rename));
        assertThrows(ServiceException.class,
            () -> matchService.deleteWithValidByIds(java.util.List.of(match.getId()), true));
    }

    @Test
    void scoresAreWritableOnlyWhileStageMatchAndRoundAreGaming() {
        Long tournamentId = newTournament("分数守卫");
        TStage stage = newStage(tournamentId);
        TMatch match = newMatch(tournamentId, stage.getId());
        TMatchRound round = newRound(tournamentId, match.getId(), 1L);
        TCompetitor competitor = newCompetitor(tournamentId, stage.getId(), "选手A", StageConstants.SLOT_PLAYER);
        newParticipant(tournamentId, match.getId(), competitor.getId(), 0L);

        TRoundScoreBo score = new TRoundScoreBo();
        score.setTournamentId(tournamentId);
        score.setRoundId(round.getId());
        score.setCompetitorId(competitor.getId());
        score.setScore(BigDecimal.TEN);

        assertThrows(ServiceException.class, () -> scoreService.insertByBo(score),
            "未开赛时不能直接写原始分");

        setStageStatus(stage.getId(), StageConstants.STAGE_GAMING);
        setMatchStatus(match.getId(), StageConstants.MATCH_GAMING);
        setRoundStatus(round.getId(), StageConstants.MATCH_GAMING);
        assertDoesNotThrow(() -> scoreService.insertByBo(score));

        setRoundStatus(round.getId(), StageConstants.MATCH_SETTLED);
        TRoundScoreBo update = new TRoundScoreBo();
        update.setId(score.getId());
        update.setScore(BigDecimal.ONE);
        assertThrows(ServiceException.class, () -> scoreService.updateByBo(update),
            "已结束轮次不能通过底层 CRUD 改分");
        assertTrue(scoreMapper.selectById(score.getId()).getScore().compareTo(BigDecimal.TEN) == 0);
    }

    @Test
    void participantAndCompetitorOperationalFieldsLockAfterStart() {
        Long tournamentId = newTournament("参赛数据守卫");
        TStage stage = newStage(tournamentId);
        TMatch match = newMatch(tournamentId, stage.getId());
        TCompetitor competitor = newCompetitor(
            tournamentId, stage.getId(), "选手A", StageConstants.SLOT_PLAYER);
        TMatchParticipant participant = newParticipant(
            tournamentId, match.getId(), competitor.getId(), 0L);

        setStageStatus(stage.getId(), StageConstants.STAGE_GAMING);
        setMatchStatus(match.getId(), StageConstants.MATCH_GAMING);

        TMatchParticipantBo participantPatch = new TMatchParticipantBo();
        participantPatch.setId(participant.getId());
        participantPatch.setOutcomeStatus("ADVANCE");
        assertThrows(ServiceException.class, () -> participantService.updateByBo(participantPatch));

        TCompetitorBo resultPatch = new TCompetitorBo();
        resultPatch.setId(competitor.getId());
        resultPatch.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        assertThrows(ServiceException.class, () -> competitorService.updateByBo(resultPatch));

        TCompetitorBo rename = new TCompetitorBo();
        rename.setId(competitor.getId());
        rename.setName("选手A改名");
        assertDoesNotThrow(() -> competitorService.updateByBo(rename));
        assertEquals("选手A改名", competitorMapper.selectById(competitor.getId()).getName());
    }

    @Test
    void relationsAndCurrentCompetitorMustBelongToSameCompetition() {
        Long tournamentA = newTournament("归属A");
        Long tournamentB = newTournament("归属B");
        TStage stage = newStage(tournamentA);

        TMatchBo foreignMatch = new TMatchBo();
        foreignMatch.setTournamentId(tournamentB);
        foreignMatch.setStageId(stage.getId());
        foreignMatch.setName("跨赛事场次");
        foreignMatch.setStatus(StageConstants.MATCH_PENDING);
        foreignMatch.setMatchMode("STANDARD");
        assertThrows(ServiceException.class, () -> matchService.insertByBo(foreignMatch));

        TMatch match = newMatch(tournamentA, stage.getId());
        TCompetitor attached = newCompetitor(
            tournamentA, stage.getId(), "场上选手", StageConstants.SLOT_PLAYER);
        TCompetitor other = newCompetitor(
            tournamentA, stage.getId(), "场外选手", StageConstants.SLOT_PLAYER);
        newParticipant(tournamentA, match.getId(), attached.getId(), 0L);
        setStageStatus(stage.getId(), StageConstants.STAGE_GAMING);
        setMatchStatus(match.getId(), StageConstants.MATCH_GAMING);

        assertDoesNotThrow(() -> lifecycleService.setMatchCurrentCompetitor(match.getId(), attached.getId()));
        assertThrows(ServiceException.class,
            () -> lifecycleService.setMatchCurrentCompetitor(match.getId(), other.getId()));
    }

    private Long newTournament(String name) {
        TTournament tournament = new TTournament();
        tournament.setName(name);
        tournamentMapper.insert(tournament);
        return tournament.getId();
    }

    private TStage newStage(Long tournamentId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName("淘汰赛");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(4L);
        bo.setTeamCountEnd(2L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":4,"
            + "\"advanceCount\":2,\"pairingMode\":\"SEQUENTIAL\"},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageMapper.selectById(stageService.insertByBo(bo).getId());
    }

    private TMatch newMatch(Long tournamentId, Long stageId) {
        TMatch match = new TMatch();
        match.setTournamentId(tournamentId);
        match.setStageId(stageId);
        match.setName("测试场次");
        match.setStatus(StageConstants.MATCH_PENDING);
        match.setMatchMode("STANDARD");
        matchMapper.insert(match);
        return match;
    }

    private TMatchRound newRound(Long tournamentId, Long matchId, Long sequence) {
        TMatchRound round = new TMatchRound();
        round.setTournamentId(tournamentId);
        round.setMatchId(matchId);
        round.setRoundSequence(sequence);
        round.setStatus(StageConstants.MATCH_PENDING);
        roundMapper.insert(round);
        return round;
    }

    private TCompetitor newCompetitor(Long tournamentId, Long stageId, String name, String slotKind) {
        TCompetitor competitor = new TCompetitor();
        competitor.setTournamentId(tournamentId);
        competitor.setStageId(stageId);
        competitor.setType(0L);
        competitor.setName(name);
        competitor.setNumber(String.valueOf(System.nanoTime()));
        competitor.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(competitor);
        return competitor;
    }

    private TMatchParticipant newParticipant(Long tournamentId, Long matchId,
                                             Long competitorId, Long slot) {
        TMatchParticipant participant = new TMatchParticipant();
        participant.setTournamentId(tournamentId);
        participant.setMatchId(matchId);
        participant.setCompetitorId(competitorId);
        participant.setDisplaySlotIndex(slot);
        participant.setSlotKind(StageConstants.SLOT_PLAYER);
        participant.setOutcomeStatus("PENDING");
        participantMapper.insert(participant);
        return participant;
    }

    private void setStageStatus(Long stageId, String status) {
        TStage patch = new TStage();
        patch.setId(stageId);
        patch.setStatus(status);
        stageMapper.updateById(patch);
    }

    private void setMatchStatus(Long matchId, String status) {
        TMatch patch = new TMatch();
        patch.setId(matchId);
        patch.setStatus(status);
        matchMapper.updateById(patch);
    }

    private void setRoundStatus(Long roundId, String status) {
        TMatchRound patch = new TMatchRound();
        patch.setId(roundId);
        patch.setStatus(status);
        roundMapper.updateById(patch);
    }
}
