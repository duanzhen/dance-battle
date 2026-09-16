package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多裁判累计打分(VOTING / RANKING)内核:{@code TScoredMatchServiceImpl} 此前只覆盖到 3.8%。
 *
 * <p>覆盖:裁判逐次提交只累计不结算(accumulateScores)、用全部裁判分重算总分排名(computeAll)、
 * 回写 participant(writeParticipantScores),以及 completeStage 时的统一结算
 * (settleScoredMatches):小组赛按组结果定胜负、淘汰赛把胜者送去晋级。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ScoredMatchSettlementTest {

    private static final String DB_PATH = "target/scored-match-settlement.db";

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
    private TRefereeMapper refereeMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private ITScoredMatchService scoredMatchService;
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

    private TStageVo newStage(Long tid, String name, String mode, int start, int end, String rule) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) start);
        bo.setTeamCountEnd((long) end);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(rule);
        return stageService.insertByBo(bo);
    }

    private Long insertPending(Long tid, Long stageId, String name, String number, int seed) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank((long) seed);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        return c.getId();
    }

    /** 建裁判并整体分配到赛段(assignReferees 是覆盖语义,多裁判需一次传入) */
    private Long insertReferee(Long tid, Long stageId, String name, Long... others) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        List<Long> all = new ArrayList<>();
        for (Long o : others) {
            all.add(o);
        }
        all.add(r.getId());
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stageId);
        bo.setTournamentId(tid);
        bo.setRefereeIds(all);
        refereeStageService.assignReferees(bo);
        return r.getId();
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

    private BigDecimal participantScore(Long matchId, Long competitorId) {
        TMatchParticipant p = participantMapper.selectOne(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .eq(TMatchParticipant::getCompetitorId, competitorId)
            .last("limit 1"));
        return p == null ? null : p.getScoreValue();
    }

    /** 某裁判给本场各参赛方打分(不传 outcome:VOTING/RANKING 只看分数) */
    private void submitScores(Long matchId, Long refereeId, Map<Long, BigDecimal> byCompetitor) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        byCompetitor.forEach((cid, score) -> {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(cid);
            se.setScore(score);
            scores.add(se);
        });
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    private static String groupVotingRule(int groupCount, int advancePerGroup) {
        return "{\"mode\":\"GROUP\",\"group\":{\"groupCount\":" + groupCount + ",\"winPoints\":3,"
            + "\"drawPoints\":1,\"lossPoints\":0,\"advancePerGroup\":" + advancePerGroup + "},"
            + "\"scoring\":{\"matchMode\":\"VOTING\",\"aggregateRule\":\"SUM\"}}";
    }

    private static String knockoutVotingRule() {
        return "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":2,\"advanceCount\":1,"
            + "\"format\":\"BO1\",\"pairingMode\":\"SEED\"},"
            + "\"scoring\":{\"matchMode\":\"VOTING\",\"aggregateRule\":\"SUM\"}}";
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 投票计分小组赛:裁判打分只累计不结算,完成赛段时统一结算并按组积分晋级。 */
    @Test
    void votingGroupStageAccumulatesThenSettlesByPoints() {
        Long tid = newTournament("投票计分小组赛");
        TStageVo stage = newStage(tid, "小组赛", "GROUP", 4, 2, groupVotingRule(2, 1));
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }
        Long judge = insertReferee(tid, stage.getId(), "裁判1");

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(2, matches.size(), "4 人 2 组应生成 2 场");
        assertTrue(matches.stream().allMatch(m -> "VOTING".equals(m.getMatchMode())),
            "组内对抗应为投票计分模式");

        for (TMatch m : matches) {
            List<TMatchParticipant> parts = realParticipants(m.getId());
            Map<Long, BigDecimal> scores = new HashMap<>();
            scores.put(parts.get(0).getCompetitorId(), BigDecimal.valueOf(10));
            scores.put(parts.get(1).getCompetitorId(), BigDecimal.valueOf(5));
            submitScores(m.getId(), judge, scores);
            assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(m.getId()).getStatus(),
                "累计打分阶段不即时结算,由完成赛段统一处理");
        }

        List<TMatchParticipant> first = realParticipants(matches.get(0).getId());
        assertEquals(0, first.get(0).getScoreValue().compareTo(BigDecimal.valueOf(10)),
            "总分已回写到参赛方");
        assertEquals(0, first.get(1).getScoreValue().compareTo(BigDecimal.valueOf(5)));

        lifecycleService.completeStage(stage.getId());

        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertTrue(matchesOf(stage.getId()).stream()
                .allMatch(m -> StageConstants.MATCH_SETTLED.equals(m.getStatus())),
            "完成赛段应把未结算场次一并结算");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "每组积分第 1 名晋级");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
    }

    /** 多名裁判的分按汇总规则聚合后排名(SUM:A=10+2, B=5+1)。 */
    @Test
    void multipleRefereeScoresAreAggregated() {
        Long tid = newTournament("多裁判分数聚合");
        TStageVo stage = newStage(tid, "小组赛", "GROUP", 2, 1, groupVotingRule(1, 1));
        Long left = insertPending(tid, stage.getId(), "选手A", "1", 1);
        Long right = insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long j1 = insertReferee(tid, stage.getId(), "裁判1");
        Long j2 = insertReferee(tid, stage.getId(), "裁判2", j1);

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);

        submitScores(match.getId(), j1, Map.of(left, BigDecimal.valueOf(10), right, BigDecimal.valueOf(5)));
        submitScores(match.getId(), j2, Map.of(left, BigDecimal.valueOf(2), right, BigDecimal.valueOf(1)));

        List<TMatchParticipant> parts = realParticipants(match.getId());
        Long leftId = parts.get(0).getCompetitorId();
        Long rightId = parts.get(1).getCompetitorId();
        assertEquals(0, participantScore(match.getId(), leftId).compareTo(BigDecimal.valueOf(12)),
            "两名裁判的分应按 SUM 汇总");
        assertEquals(0, participantScore(match.getId(), rightId).compareTo(BigDecimal.valueOf(6)));
        assertEquals(1L, parts.get(0).getRankInMatch(), "总分高者本场排名第 1");
    }

    /** 投票计分淘汰赛:完成赛段时结算,总分高者按场次规则晋级下一赛段。 */
    @Test
    void votingKnockoutStageSettlesWinner() {
        Long tid = newTournament("投票计分淘汰赛");
        TStageVo stage = newStage(tid, "决赛", "KNOCKOUT", 2, 1, knockoutVotingRule());
        Long left = insertPending(tid, stage.getId(), "选手A", "1", 1);
        Long right = insertPending(tid, stage.getId(), "选手B", "2", 2);
        Long judge = insertReferee(tid, stage.getId(), "裁判1");

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        matchResultService.startMatch(match.getId());
        submitScores(match.getId(), judge, Map.of(left, BigDecimal.valueOf(9), right, BigDecimal.valueOf(4)));
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(match.getId()).getStatus());

        lifecycleService.completeStage(stage.getId());

        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "决赛胜者应标记晋级下一赛段");
        assertEquals(1, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
    }

    /** 统一结算的边界:非投票/计分模式的场次跳过;已结算后重复调用是空操作。 */
    @Test
    void settleScoredMatchesSkipsNonScoredAndSettled() {
        // 判定制(STANDARD)场次不属于累计打分类,应被跳过
        Long tid = newTournament("统一结算边界");
        TStageVo knockout = newStage(tid, "决赛", "KNOCKOUT", 2, 1,
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":2,\"advanceCount\":1},"
                + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        insertPending(tid, knockout.getId(), "选手A", "1", 1);
        insertPending(tid, knockout.getId(), "选手B", "2", 2);
        lifecycleService.startStage(knockout.getId());
        TMatch km = matchesOf(knockout.getId()).get(0);
        matchResultService.startMatch(km.getId());
        scoredMatchService.settleScoredMatches(knockout.getId());
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(km.getId()).getStatus(),
            "判定制场次不参与累计打分结算");

        // 已全部结算后再调用:空操作,不抛异常
        Long tid2 = newTournament("重复结算");
        TStageVo group = newStage(tid2, "小组赛", "GROUP", 2, 1, groupVotingRule(1, 1));
        Long l = insertPending(tid2, group.getId(), "选手A", "1", 1);
        Long r = insertPending(tid2, group.getId(), "选手B", "2", 2);
        Long judge = insertReferee(tid2, group.getId(), "裁判1");
        lifecycleService.startStage(group.getId());
        TMatch gm = matchesOf(group.getId()).get(0);
        submitScores(gm.getId(), judge, Map.of(l, BigDecimal.TEN, r, BigDecimal.ONE));
        scoredMatchService.settleScoredMatches(group.getId());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(gm.getId()).getStatus());
        assertNotNull(participantScore(gm.getId(), l));

        scoredMatchService.settleScoredMatches(group.getId());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(gm.getId()).getStatus(),
            "重复结算应保持幂等");
    }
}
