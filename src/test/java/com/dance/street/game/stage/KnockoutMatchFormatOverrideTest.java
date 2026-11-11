package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.MatchFormat;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 淘汰赛开赛传入本场临时 BO:写入 t_match.format,不写赛段配置;
 * 开始/重置先删光旧轮次再按本场 format 重建;平局补局不得把 BO3 误判成 BO5。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class KnockoutMatchFormatOverrideTest {

    private static final String DB_PATH = "target/knockout-match-format-override.db";

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
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private ITMatchService matchService;

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newKnockoutStage(Long tid, String name, int teams, int advance, String format) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) teams);
        bo.setTeamCountEnd((long) advance);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"format\":\"" + format + "\",\"knockout\":{\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"pairingMode\":\"SEED\"},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageService.insertByBo(bo);
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
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchRound> roundsOf(Long matchId) {
        return matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, matchId)
            .orderByAsc(TMatchRound::getRoundSequence));
    }

    private List<TMatchParticipant> realParticipants(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private int roundCount(Long matchId) {
        return roundsOf(matchId).size();
    }

    private String stageFormat(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return MatchFormat.of(stage.getRuleConfig()).name();
    }

    private TMatch pendingMatch(Long tid, String stageName, String stageFormat) {
        TStageVo stage = newKnockoutStage(tid, stageName, 2, 1, stageFormat);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        lifecycleService.startStage(stage.getId());
        return matchesOf(stage.getId()).get(0);
    }

    private void directorJudgeGame(Long matchId, Boolean leftWin) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        if (leftWin == null) {
            outcomes.put(parts.get(0).getCompetitorId(), "DRAW");
            outcomes.put(parts.get(1).getCompetitorId(), "DRAW");
        } else {
            outcomes.put(parts.get(0).getCompetitorId(), leftWin ? "WIN" : "LOSS");
            outcomes.put(parts.get(1).getCompetitorId(), leftWin ? "LOSS" : "WIN");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 传入 BO3 开赛:整场按 3 局新建,并写入本场 format,赛段默认 BO1 不变。 */
    @Test
    void startWithBo3BuildsThreeRounds() {
        TMatch match = pendingMatch(newTournament("开赛传BO3"), "决赛", "BO1");
        assertEquals(1, roundCount(match.getId()), "初始化应只有第 1 局占位");
        assertNull(matchMapper.selectById(match.getId()).getFormat(), "待开始尚未写入本场 format");

        matchResultService.startMatch(match.getId(), "BO3");

        List<TMatchRound> rounds = roundsOf(match.getId());
        assertEquals(3, rounds.size(), "传入 BO3 开赛应新建 3 局");
        assertEquals(StageConstants.MATCH_GAMING, rounds.get(0).getStatus());
        assertEquals(StageConstants.MATCH_PENDING, rounds.get(1).getStatus());
        assertEquals(StageConstants.MATCH_PENDING, rounds.get(2).getStatus());
        assertEquals("BO3", matchMapper.selectById(match.getId()).getFormat());
        assertEquals("BO1", stageFormat(match.getStageId()), "赛段默认不应被改掉");

        TMatchVo vo = matchService.queryById(match.getId());
        assertEquals("BO3", vo.getFormat());
        assertEquals(3, vo.getBestOf());
        assertEquals(2, vo.getRequiredWins());
    }

    /** 不传 format 仍走赛段默认,并且把赛段默认写入本场 format。 */
    @Test
    void startWithoutFormatUsesStageDefaultAndPersistsIt() {
        TMatch match = pendingMatch(newTournament("不传参走赛段默认"), "决赛", "BO3");

        TMatchVo before = matchService.queryById(match.getId());
        assertNull(before.getFormat());
        assertEquals(3, before.getBestOf(), "待开始且 format 为空时应展示赛段默认 BO3");
        assertEquals(2, before.getRequiredWins());

        matchResultService.startMatch(match.getId());

        assertEquals("BO3", matchMapper.selectById(match.getId()).getFormat(),
            "即使用的是赛段默认,也要写入本场 format");
        assertEquals(3, roundCount(match.getId()), "不传参应按赛段 BO3 生成 3 局");
        TMatchVo vo = matchService.queryById(match.getId());
        assertEquals("BO3", vo.getFormat());
        assertEquals(3, vo.getBestOf());
        assertEquals(2, vo.getRequiredWins());
    }

    /** 待开始 bestOf 用赛段默认;开赛后改用本场 format。 */
    @Test
    void pendingBestOfUsesStageDefaultThenSwitchesToMatchFormat() {
        TMatch match = pendingMatch(newTournament("待开始展示赛段默认"), "决赛", "BO5");

        TMatchVo pending = matchService.queryById(match.getId());
        assertNull(pending.getFormat());
        assertEquals(5, pending.getBestOf());
        assertEquals(3, pending.getRequiredWins());

        matchResultService.startMatch(match.getId(), "BO3");

        TMatchVo started = matchService.queryById(match.getId());
        assertEquals("BO3", started.getFormat());
        assertEquals(3, started.getBestOf(), "开赛后应展示本场 BO3,而不是赛段 BO5");
        assertEquals(2, started.getRequiredWins());
        assertEquals("BO5", stageFormat(match.getStageId()));
    }

    /** 先按 BO5 开赛再取消,再以 BO3 重开:必须删掉第 4、5 局,2:0 即可结束而不是先赢 3 局。 */
    @Test
    void restartFromBo5ToBo3DeletesExtraRoundsAndSweepsAtTwoWins() {
        TMatch match = pendingMatch(newTournament("BO5改BO3重开"), "决赛", "BO1");

        matchResultService.startMatch(match.getId(), "BO5");
        assertEquals(5, roundCount(match.getId()), "先按 BO5 应生成 5 局");
        assertEquals("BO5", matchMapper.selectById(match.getId()).getFormat());

        matchResultService.cancelStartMatch(match.getId());
        TMatch cancelled = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_PENDING, cancelled.getStatus());
        assertEquals("BO5", cancelled.getFormat(), "取消开始后本场 format 可保留");

        matchResultService.startMatch(match.getId(), "BO3");
        List<TMatchRound> rounds = roundsOf(match.getId());
        assertEquals(3, rounds.size(), "以 BO3 重开应删掉旧的第 4、5 局并只留 3 局");
        assertEquals(List.of(1L, 2L, 3L),
            rounds.stream().map(TMatchRound::getRoundSequence).toList());
        assertEquals("BO3", matchMapper.selectById(match.getId()).getFormat(), "再次开赛应覆盖为本场 BO3");

        directorJudgeGame(match.getId(), true);
        TMatch afterFirst = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, afterFirst.getStatus(),
            "若仍按 BO1/残留 BO5 理解会在第一局或未满 2 胜时走偏;BO3 第一局后应继续");

        directorJudgeGame(match.getId(), true);
        TMatch afterSweep = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, afterSweep.getStatus(), "2:0 不自动结束,可提前确认");
        assertNotNull(afterSweep.getResultJson(), "2:0 应暂存整场结果");

        TMatchVo vo = matchService.queryById(match.getId());
        assertEquals("BO3", vo.getFormat());
        assertEquals(3, vo.getBestOf());
        assertEquals(2, vo.getRequiredWins());
        assertEquals(2, vo.getSeriesLeftWins());
        assertEquals(0, vo.getSeriesRightWins());
        assertEquals("LEFT", vo.getSeriesWinnerSide());

        matchResultService.publishResult(match.getId());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(match.getId()).getStatus());
        assertEquals("BO1", stageFormat(match.getStageId()), "全程不应改写赛段默认");
    }

    /** 重置整场按已写入的本场 format 删光重建,不会掉回赛段默认;非法 format 拒绝且不改变待开始场次。 */
    @Test
    void resetMatchKeepsStartedFormatAndInvalidStartFormatIsRejected() {
        TMatch match = pendingMatch(newTournament("重置保持本场BO"), "决赛", "BO1");

        assertThrows(ServiceException.class, () -> matchResultService.startMatch(match.getId(), "BO7"));
        TMatch rejected = matchMapper.selectById(match.getId());
        assertEquals(1, roundCount(match.getId()), "非法 format 不应改写占位轮次");
        assertEquals(StageConstants.MATCH_PENDING, rejected.getStatus());
        assertNull(rejected.getFormat(), "非法 format 不应写入本场");

        matchResultService.startMatch(match.getId(), "BO3");
        directorJudgeGame(match.getId(), true);
        matchResultService.resetMatch(match.getId());

        List<TMatchRound> rounds = roundsOf(match.getId());
        assertEquals(3, rounds.size(), "整场重置应按已开赛的 BO3 重建 3 局,而不是赛段 BO1");
        assertEquals(StageConstants.MATCH_GAMING, rounds.get(0).getStatus());
        assertEquals(StageConstants.MATCH_PENDING, rounds.get(1).getStatus());
        assertEquals("BO3", matchMapper.selectById(match.getId()).getFormat());
        TMatchVo vo = matchService.queryById(match.getId());
        assertEquals(3, vo.getBestOf());
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus());
    }

    /**
     * 平局把 BO3 拖出第 4 局后,match.format 仍是 BO3(先赢 2 局)。
     * 若被误判成 BO5,2:1 还不会结束、还会继续开第 5 局。
     */
    @Test
    void tiebreakGamesDoNotPromoteBo3ToBo5() {
        TMatch match = pendingMatch(newTournament("BO3平局补局仍先赢两局"), "决赛", "BO1");
        matchResultService.startMatch(match.getId(), "BO3");

        directorJudgeGame(match.getId(), true);   // 1:0
        directorJudgeGame(match.getId(), false);  // 1:1
        directorJudgeGame(match.getId(), null);   // 平,仍 1:1,补第 4 局

        assertEquals(4, roundCount(match.getId()), "三局后仍未有人赢满 2 局,应补出第 4 局");
        assertEquals("BO3", matchMapper.selectById(match.getId()).getFormat(),
            "补局不得改写本场 format");
        TMatchVo mid = matchService.queryById(match.getId());
        assertEquals(3, mid.getBestOf());
        assertEquals(2, mid.getRequiredWins());
        assertEquals(1, mid.getSeriesLeftWins());
        assertEquals(1, mid.getSeriesRightWins());

        directorJudgeGame(match.getId(), true);   // 2:1,BO3 应分出胜负
        TMatch decided = matchMapper.selectById(match.getId());
        assertEquals("BO3", decided.getFormat());
        assertEquals(4, roundCount(match.getId()), "BO3 在 2:1 时不应再开第 5 局");
        // 已打满/超出最多 3 局且已有人赢满:导演直接判定会结算,而不是按 BO5 继续等第 3 胜
        assertEquals(StageConstants.MATCH_SETTLED, decided.getStatus(),
            "若被当成 BO5(先赢 3 局)则会继续进行;BO3 在 2:1 且已打满应直接结束");

        TMatchVo vo = matchService.queryById(match.getId());
        assertEquals(3, vo.getBestOf());
        assertEquals(2, vo.getRequiredWins());
        assertEquals(2, vo.getSeriesLeftWins());
        assertEquals(1, vo.getSeriesRightWins());
        assertEquals("LEFT", vo.getSeriesWinnerSide());
        assertTrue(roundCount(match.getId()) >= 4);
        assertEquals("BO1", stageFormat(match.getStageId()));
    }
}
