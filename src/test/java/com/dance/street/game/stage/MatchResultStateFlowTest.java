package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITRefereeStageService;
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
 * 场次结果链路的三个动作此前完全没有测试覆盖:
 * <ul>
 *   <li>多裁判投票聚合(aggregateRefereeVotes):全员判完才结算、票数持平判平;</li>
 *   <li>公布结果(publishResult):MANUAL 模式下裁判判完先暂存,由导播台确认后生效;</li>
 *   <li>回退(resetMatch / cancelStartMatch / clearMatchState):清分、清下游占位、状态回退。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class MatchResultStateFlowTest {

    private static final String DB_PATH = "target/match-result-state-flow.db";

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
    private TRefereeMapper refereeMapper;
    @Autowired
    private com.dance.street.game.mapper.TRoundScoreMapper roundScoreMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private ITRefereeStageService refereeStageService;

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newKnockoutStage(Long tid, String name, int teams, int advance,
                                      boolean thirdPlace, String publishMode) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) teams);
        bo.setTeamCountEnd((long) advance);
        bo.setIsInitialized(0L);
        String third = thirdPlace ? ",\"thirdPlaceMatch\":true" : "";
        String publish = publishMode == null ? "" : ",\"publishMode\":\"" + publishMode + "\"";
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\""
            + third + publish + "},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageService.insertByBo(bo);
    }

    /** 建裁判并分配到赛段(多裁判投票按赛段裁判数判定"是否判完") */
    private Long assignReferee(Long tid, Long stageId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stageId);
        bo.setTournamentId(tid);
        bo.setRefereeIds(List.of(r.getId()));
        refereeStageService.assignReferees(bo);
        return r.getId();
    }

    /** 追加一名裁判(保留已有分配) */
    private Long addReferee(Long tid, Long stageId, String name, Long existing) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stageId);
        bo.setTournamentId(tid);
        bo.setRefereeIds(List.of(existing, r.getId()));
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

    private int roundCount(Long matchId) {
        return matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, matchId)).size();
    }

    /** 本场已落库的打分明细条数(含投票行) */
    private long scoreRows(Long matchId) {
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (roundIds.isEmpty()) {
            return 0;
        }
        return roundScoreMapper.selectCount(Wrappers.<com.dance.street.game.domain.TRoundScore>lambdaQuery()
            .in(com.dance.street.game.domain.TRoundScore::getRoundId, roundIds));
    }

    /** 某裁判投票:leftWin=true 表示左方胜 */
    private void vote(Long matchId, Long refereeId, boolean leftWin) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(parts.get(0).getCompetitorId(), leftWin ? "WIN" : "LOSS");
        outcomes.put(parts.get(1).getCompetitorId(), leftWin ? "LOSS" : "WIN");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 某裁判投平 */
    private void voteDraw(Long matchId, Long refereeId) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        Map<Long, String> outcomes = new HashMap<>();
        parts.forEach(p -> outcomes.put(p.getCompetitorId(), "DRAW"));
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 导播台直接判定(无裁判):按槽位顺序前者胜 */
    private void finishByDirector(Long matchId) {
        matchResultService.startMatch(matchId);
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            outcomes.put(parts.get(i).getCompetitorId(), i == 0 ? "WIN" : "LOSS");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 多裁判投票:未判完只累计不结算;全员判完才按票数结算。 */
    @Test
    void refereeVotesWaitForAllThenSettle() {
        Long tid = newTournament("多裁判投票");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, null);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = assignReferee(tid, stage.getId(), "裁判1");
        Long j2 = addReferee(tid, stage.getId(), "裁判2", j1);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());

        vote(match.getId(), j1, true);
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus(),
            "只判了一半时不应结算");
        assertEquals(2, scoreRows(match.getId()), "投票按 WIN=1 / LOSS=0 各记一行");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "未结算时不应有人晋级");

        vote(match.getId(), j2, true);
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(match.getId()).getStatus(),
            "全员判完后应立即结算");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
    }

    /** 裁判意见左右各一 → 票数持平 → 判定平局并加赛一轮。 */
    @Test
    void splitRefereeVotesBecomeDrawAndReplay() {
        Long tid = newTournament("裁判意见持平");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, null);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = assignReferee(tid, stage.getId(), "裁判1");
        Long j2 = addReferee(tid, stage.getId(), "裁判2", j1);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());

        vote(match.getId(), j1, true);
        vote(match.getId(), j2, false);

        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus(),
            "票数持平按平局处理,场次保持进行中等待加赛");
        assertEquals(2, roundCount(match.getId()), "平局应自动新增加赛轮");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
    }

    /** 全员判平 → 同样进入平局加赛,不产生正式结果。 */
    @Test
    void allRefereeDrawsBecomeReplay() {
        Long tid = newTournament("全员判平");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, null);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = assignReferee(tid, stage.getId(), "裁判1");
        Long j2 = addReferee(tid, stage.getId(), "裁判2", j1);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());

        voteDraw(match.getId(), j1);
        voteDraw(match.getId(), j2);

        assertEquals(2, roundCount(match.getId()), "全员判平应进入加赛轮");
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus());
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
    }

    /** MANUAL 公布模式:裁判判完只暂存结果,导播台点「公布」才落结果。 */
    @Test
    void manualPublishModeStoresThenPublishes() {
        Long tid = newTournament("手动公布结果");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, "MANUAL");
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = assignReferee(tid, stage.getId(), "裁判1");

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        vote(match.getId(), j1, true);

        TMatch afterVote = matchMapper.selectById(match.getId());
        assertEquals(StageConstants.MATCH_GAMING, afterVote.getStatus(),
            "MANUAL 模式判完不立即结束场次");
        assertNotNull(afterVote.getResultJson(), "应暂存待公布结果");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "公布前不应产生晋级");

        MatchResultVo vo = matchResultService.publishResult(match.getId());
        assertNotNull(vo);
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(match.getId()).getStatus(),
            "公布后场次结束");
        assertNull(matchMapper.selectById(match.getId()).getResultJson(), "公布后应清空暂存结果");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        assertThrows(ServiceException.class, () -> matchResultService.publishResult(match.getId()),
            "已结束的场次不可再公布");
    }

    /** 暂无待公布结果时点「公布」应给出明确提示,而不是静默失败。 */
    @Test
    void publishWithoutPendingResultIsRejected() {
        Long tid = newTournament("无待公布结果");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, "MANUAL");
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> matchResultService.publishResult(match.getId()));
        assertTrue(ex.getMessage().contains("暂无待公布结果"),
            "应说明还没有可公布的结果,实际: " + ex.getMessage());
    }

    /** 取消开始(误触回退):场次回待开始并清空已提交判罚。 */
    @Test
    void cancelStartMatchClearsVotesAndReverts() {
        Long tid = newTournament("取消开始场次");
        TStageVo stage = newKnockoutStage(tid, "决赛", 2, 1, false, null);
        insertPending(tid, stage.getId(), "选手A", "1", 1);
        insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = assignReferee(tid, stage.getId(), "裁判1");
        addReferee(tid, stage.getId(), "裁判2", j1);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        vote(match.getId(), j1, true);
        assertEquals(2, scoreRows(match.getId()), "前置条件:已有 1 名裁判投票");

        matchResultService.cancelStartMatch(match.getId());

        assertEquals(StageConstants.MATCH_PENDING, matchMapper.selectById(match.getId()).getStatus(),
            "取消开始应回到待开始");
        assertEquals(0, scoreRows(match.getId()), "取消开始应清空已提交判罚");
        assertTrue(realParticipants(match.getId()).stream()
                .allMatch(p -> "PENDING".equals(p.getOutcomeStatus())));

        assertThrows(ServiceException.class, () -> matchResultService.cancelStartMatch(match.getId()),
            "待开始的场次不能再取消开始");
    }

    /** 重启已结算场次:清分、清参赛方结果与名次,并回退填入季军赛的败者占位。 */
    @Test
    void resetSettledMatchClearsResultAndThirdPlaceSlot() {
        Long tid = newTournament("重启场次");
        TStageVo stage = newKnockoutStage(tid, "半决赛", 4, 2, true, null);
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(stage.getId());

        List<TMatch> matches = matchesOf(stage.getId());
        TMatch third = matches.stream().filter(m -> "季军赛".equals(m.getName()))
            .findFirst().orElseThrow();
        List<TMatch> semis = matches.stream().filter(m -> !"季军赛".equals(m.getName())).toList();
        assertEquals(2, semis.size());

        Long loserOfFirst = null;
        for (TMatch semi : semis) {
            List<TMatchParticipant> before = realParticipants(semi.getId());
            finishByDirector(semi.getId());
            if (semi.getId().equals(semis.get(0).getId())) {
                loserOfFirst = before.get(1).getCompetitorId();
            }
        }
        assertEquals(2, realParticipants(third.getId()).size(), "前置条件:季军赛已收到两名败者");

        matchResultService.resetMatch(semis.get(0).getId());

        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(semis.get(0).getId()).getStatus(),
            "重启后场次回到进行中等待重新判罚");
        assertEquals(0, scoreRows(semis.get(0).getId()), "重启应清空本场分数");
        assertEquals(1, realParticipants(third.getId()).size(),
            "季军赛里该场败者的占位应被清掉");
        TCompetitor loser = competitorMapper.selectById(loserOfFirst);
        assertEquals(OutcomeStatusEnum.PENDING.getCode(), loser.getOutcomeStatus(),
            "重启后参赛方赛段结果回退待定");
        assertNull(loser.getFinalRank(), "重启后应清空残留名次");

        // 赛段已结束时禁止重启场次
        TStage settled = new TStage();
        settled.setId(stage.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);
        assertThrows(ServiceException.class,
            () -> matchResultService.resetMatch(semis.get(0).getId()),
            "赛段已结束不可重启场次");
    }
}
