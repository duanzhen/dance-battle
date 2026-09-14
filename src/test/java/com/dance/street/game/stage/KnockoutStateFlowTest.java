package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 淘汰赛的状态流转:轮空自动结算、平局加赛回环、季军赛败者路由。
 *
 * <p>这三条此前完全没有测试覆盖(settleByeMatch / resolveKnockoutByeWinner /
 * createReplayRound / 败者路由),而淘汰赛是最常用的赛制。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class KnockoutStateFlowTest {

    private static final String DB_PATH = "target/knockout-state-flow.db";

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

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newKnockoutStage(Long tid, String name, int teams, int advance, boolean thirdPlace) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) teams);
        bo.setTeamCountEnd((long) advance);
        bo.setIsInitialized(0L);
        String third = thirdPlace ? ",\"thirdPlaceMatch\":true" : "";
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\"" + third
            + "},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
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

    /** 导播台判定一场:按槽位顺序前者胜 */
    private void finishByDirector(Long matchId) {
        matchResultService.startMatch(matchId);
        List<TMatchParticipant> parts = realParticipants(matchId);
        assertTrue(parts.size() >= 2, "真人场次应有至少 2 名参赛方");
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            outcomes.put(parts.get(i).getCompetitorId(), i == 0 ? "WIN" : "LOSS");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 轮空:5 人打 8 人签表 → 3 场轮空自动判胜并标记晋级,剩 1 场真人场正常判定。 */
    @Test
    void byeMatchesAutoSettleAndAdvance() {
        Long tid = newTournament("轮空流转");
        TStageVo stage = newKnockoutStage(tid, "8进4", 8, 4, false);
        for (int i = 1; i <= 5; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(4, matches.size(), "8 人签表应有 4 场首轮");
        assertTrue(matches.stream().allMatch(m -> StageConstants.MATCH_PENDING.equals(m.getStatus())),
            "淘汰赛场次应由导播台逐场开始");

        int settled = lifecycleService.settleByeMatches(stage.getId());
        assertEquals(3, settled, "5 人 8 签表应有 3 场轮空");
        assertEquals(3, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "轮空胜者应直接标记晋级");

        TMatch real = matches.stream()
            .filter(m -> StageConstants.MATCH_PENDING.equals(
                matchMapper.selectById(m.getId()).getStatus()))
            .findFirst().orElseThrow(() -> new AssertionError("应剩一场真人场"));
        finishByDirector(real.getId());

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(4, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
    }

    /** 平局加赛:淘汰赛判平不结算,自动新增一轮,场次保持进行中;加赛判出胜负后才落结果。 */
    @Test
    void drawSpawnsReplayRoundThenResolves() {
        Long tid = newTournament("平局加赛");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        assertEquals(1, matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())).size(), "初始应有 1 个轮次");

        List<TMatchParticipant> parts = realParticipants(match.getId());
        SubmitResultBo draw = new SubmitResultBo();
        draw.setMatchId(match.getId());
        Map<Long, String> drawOutcomes = new HashMap<>();
        parts.forEach(p -> drawOutcomes.put(p.getCompetitorId(), "DRAW"));
        draw.setOutcomes(drawOutcomes);
        matchResultService.submitResult(draw);

        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus(),
            "平局后场次应保持进行中");
        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())
            .orderByAsc(TMatchRound::getRoundSequence));
        assertEquals(2, rounds.size(), "平局应自动新增一轮加赛");
        assertEquals(StageConstants.MATCH_SETTLED, rounds.get(0).getStatus(), "原轮次应置为已结算");
        assertEquals(StageConstants.MATCH_GAMING, rounds.get(1).getStatus(), "加赛轮应为进行中");
        assertTrue(realParticipants(match.getId()).stream()
                .allMatch(p -> "PENDING".equals(p.getOutcomeStatus())),
            "平局轮不产生正式结果,参与方应回退待判定");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "平局时不应有人晋级");

        SubmitResultBo replay = new SubmitResultBo();
        replay.setMatchId(match.getId());
        Map<Long, String> replayOutcomes = new HashMap<>();
        replayOutcomes.put(parts.get(0).getCompetitorId(), "WIN");
        replayOutcomes.put(parts.get(1).getCompetitorId(), "LOSS");
        replay.setOutcomes(replayOutcomes);
        matchResultService.submitResult(replay);

        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(match.getId()).getStatus(),
            "加赛判出胜负后场次应结算");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "决赛胜者应标记晋级");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()),
            "败者应标记淘汰");

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
    }

    /** 季军赛:4 人半决赛开启季军赛后,两场半决赛的败者被路由到季军赛场次。 */
    @Test
    void thirdPlaceMatchRoutesLosers() {
        Long tid = newTournament("季军赛连线");
        TStageVo stage = newKnockoutStage(tid, "半决赛", 4, 2, true);
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(3, matches.size(), "4 人半决赛开启季军赛应生成 3 场");

        TMatch third = matches.stream()
            .filter(m -> "季军赛".equals(m.getName()))
            .findFirst().orElseThrow(() -> new AssertionError("应生成季军赛场次"));
        assertEquals(0, realParticipants(third.getId()).size(), "季军赛初始应为空位");

        List<TMatch> semis = matches.stream()
            .filter(m -> !"季军赛".equals(m.getName()))
            .toList();
        assertEquals(2, semis.size());
        List<Long> losers = new ArrayList<>();
        for (TMatch semi : semis) {
            List<TMatchParticipant> before = realParticipants(semi.getId());
            finishByDirector(semi.getId());
            losers.add(before.get(1).getCompetitorId());
        }

        List<TMatchParticipant> thirdParts = realParticipants(third.getId());
        assertEquals(2, thirdParts.size(), "季军赛应收到两名半决赛败者");
        assertTrue(thirdParts.stream().map(TMatchParticipant::getCompetitorId).toList()
                .containsAll(losers),
            "季军赛参赛方应为两场半决赛的败者");

        finishByDirector(third.getId());
        lifecycleService.completeStage(stage.getId());
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "只有两场半决赛的胜者晋级");
    }
}
