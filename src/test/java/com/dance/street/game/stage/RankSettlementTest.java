package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.MatchRoundScoreVo;
import com.dance.street.game.domain.vo.RankDetailVo;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排名赛的赛段级结算:单圈多人挂同一场次,裁判按维度打分,完成赛段后按总分取前 N 名晋级
 * (settleRankStage / settleRankMatch / getRankDetail)。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RankSettlementTest {

    private static final String DB_PATH = "target/rank-settlement.db";

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
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITMatchService matchService;

    /**
     * 排名赛:单圈 4 人,1 名裁判按单维度打分;完成赛段后按总分取前 2 名晋级,
     * 并可通过排名明细查询各维度聚合分。
     */
    @Test
    void rankStageSettlesByAggregatedScore() {
        Long tid = newTournament("排名赛结算");
        Long judge = insertReferee(tid, "裁判A");
        TStageVo stage = newStage(tid, "排名赛", 4, 2, rankRule(2));
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(1, matches.size(), "单圈排名赛应为 1 场");
        List<TMatchParticipant> parts = realParticipants(matches.get(0).getId());
        assertEquals(4, parts.size(), "4 名选手应全部挂在本场");

        // 裁判按槽位顺序给递减分:第一名 100,第二名 90……
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matches.get(0).getId());
        bo.setRefereeId(judge);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setDimension("TECH");
            se.setScore(BigDecimal.valueOf(100 - i * 10));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);

        // 结算前的排名明细:各维度聚合分已算好
        RankDetailVo detail = lifecycleService.getRankDetail(stage.getId());
        assertNotNull(detail);
        assertEquals(1, detail.getCircles().size(), "单圈应返回 1 个分组");
        assertEquals(4, detail.getCircles().get(0).getCompetitors().size());

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "按总分取前 2 名晋级");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        // 分数最高的选手应拿到第 1 名
        Long topId = parts.get(0).getCompetitorId();
        TCompetitor top = competitorMapper.selectById(topId);
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), top.getOutcomeStatus());
        assertEquals(1L, top.getFinalRank(), "最高分选手最终名次应为 1");
    }

    /**
     * 排名赛段读取场次时,必须带上「上场名单 + 实时评分」:
     * roundScores 不能只对海选填充,否则导播台看不到选手列表与分数(本轮修复的回归)。
     * 且每个评分维度都要作为一列返回(每名选手一项维度分)。
     */
    @Test
    void rankStageExposesRosterAndLiveScores() {
        Long tid = newTournament("排名赛导播台视图");
        Long judgeA = insertReferee(tid, "裁判A");
        Long judgeB = insertReferee(tid, "裁判B");
        String twoDimRule = "{\"mode\":\"RANK\",\"advanceCount\":1,"
            + "\"scoring\":{\"type\":\"MULTI_DIM\",\"matchMode\":\"RANKING\","
            + "\"refereeAggregateRule\":\"SUM\",\"aggregateRule\":\"WEIGHTED\",\"dimensions\":["
            + "{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":50,\"maxScore\":100},"
            + "{\"key\":\"SHOW\",\"name\":\"表现力\",\"weight\":50,\"maxScore\":100}]}}";
        TStageVo stage = newStage(tid, "排名赛", 3, 1, twoDimRule);
        for (int i = 1; i <= 3; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        TMatch match = matchesOf(stage.getId()).get(0);
        List<TMatchParticipant> parts = realParticipants(match.getId());
        assertEquals(3, parts.size(), "3 名选手应全部挂在本场");

        submitDimScores(match, judgeA, parts, 90, 80);
        submitDimScores(match, judgeB, parts, 70, 60);

        TMatchBo q = new TMatchBo();
        q.setStageId(stage.getId());
        var vo = matchService.queryList(q).stream()
            .filter(m -> m.getId().equals(match.getId()))
            .findFirst().orElseThrow();
        assertNotNull(vo.getRoundScores(), "排名赛场次必须返回上场名单");
        assertEquals(3, vo.getRoundScores().size(), "3 名选手都应出现在名单里");
        assertEquals(3, vo.getRoundScores().stream().filter(r -> r.getScore() != null).count(),
            "3 名选手都应有实时总分");
        assertTrue(vo.getRoundScores().stream().allMatch(r -> r.getCompetitorName() != null),
            "名单应带选手姓名");

        // 每个评分维度都是一列;多裁判时维度分求和,维度间按 50/50 加权,同时保留每个裁判的原始单项分
        var top = vo.getRoundScores().get(0);
        assertNotNull(top.getDimensions(), "排名赛应返回各维度聚合分");
        assertEquals(2, top.getDimensions().size(), "应有两个评分维度");
        assertEquals("TECH", top.getDimensions().get(0).getKey());
        assertEquals("SHOW", top.getDimensions().get(1).getKey());
        assertEquals(0, BigDecimal.valueOf(160).compareTo(top.getDimensions().get(0).getScore()), "技术维度应为两裁判求和");
        assertEquals(0, BigDecimal.valueOf(140).compareTo(top.getDimensions().get(1).getScore()), "表现力维度应为两裁判求和");
        assertEquals(0, BigDecimal.valueOf(150).compareTo(top.getScore()), "总分应为维度分按 50/50 加权");
        assertRefereeScores(top.getDimensions().get(0).getRefereeScores(), 90, 70);
        assertRefereeScores(top.getDimensions().get(1).getRefereeScores(), 80, 60);
    }

    /**
     * 维度权重合计 100% 在「开始赛段」时校验:配置可以边改边存,开赛这一刻必须合法。
     */
    @Test
    void rankStageStartRejectsWeightsNotSummingTo100() {
        Long tid = newTournament("排名赛权重校验");
        String badRule = "{\"mode\":\"RANK\",\"advanceCount\":1,"
            + "\"scoring\":{\"type\":\"MULTI_DIM\",\"matchMode\":\"RANKING\","
            + "\"refereeAggregateRule\":\"AVG\",\"aggregateRule\":\"WEIGHTED\",\"dimensions\":["
            + "{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":50,\"maxScore\":100},"
            + "{\"key\":\"SHOW\",\"name\":\"表现力\",\"weight\":40,\"maxScore\":100}]}}";
        TStageVo stage = newStage(tid, "排名赛", 3, 1, badRule);
        for (int i = 1; i <= 3; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        ServiceException ex = assertThrows(ServiceException.class, () -> lifecycleService.startStage(stage.getId()));
        assertTrue(ex.getMessage().contains("100%"), "开赛应提示权重合计需为 100%: " + ex.getMessage());
    }

    /** 提交一名裁判对全场选手两个维度的分(按选手顺序递减) */
    private void submitDimScores(TMatch match, Long refereeId, List<TMatchParticipant> parts, int techBase, int showBase) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            for (String dim : new String[]{"TECH", "SHOW"}) {
                ScoreEntryBo se = new ScoreEntryBo();
                se.setCompetitorId(parts.get(i).getCompetitorId());
                se.setDimension(dim);
                se.setScore(BigDecimal.valueOf(("TECH".equals(dim) ? techBase : showBase) - i * 10));
                scores.add(se);
            }
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /** 断言某维度包含指定裁判原始分(顺序无关) */
    private void assertRefereeScores(List<MatchRoundScoreVo.RefereeScore> refScores, int... expected) {
        assertNotNull(refScores, "应返回每个裁判的单项分");
        assertEquals(expected.length, refScores.size());
        List<BigDecimal> actual = refScores.stream().map(MatchRoundScoreVo.RefereeScore::getScore).toList();
        for (int e : expected) {
            assertTrue(actual.stream().anyMatch(v -> v != null && v.compareTo(BigDecimal.valueOf(e)) == 0),
                "缺少裁判分 " + e + ",实际: " + actual);
        }
        assertTrue(refScores.stream().allMatch(r -> r.getRefereeName() != null), "应带裁判姓名");
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name, int start, int end, String rule) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("RANK");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) start);
        bo.setTeamCountEnd((long) end);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(rule);
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

    private Long insertReferee(Long tid, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private String rankRule(int advanceCount) {
        return "{\"mode\":\"RANK\",\"circles\":1,\"advanceCount\":" + advanceCount
            + ",\"maxScore\":100,\"scoring\":{\"type\":\"MULTI_DIM\",\"matchMode\":\"RANKING\","
            + "\"refereeAggregateRule\":\"AVG\",\"dimensions\":["
            + "{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":100,\"maxScore\":100}]}}";
    }
}
