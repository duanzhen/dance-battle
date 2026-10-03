package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
            + "{\"key\":\"TECH\",\"name\":\"技术\",\"weight\":1,\"maxScore\":100}]}}";
    }
}
