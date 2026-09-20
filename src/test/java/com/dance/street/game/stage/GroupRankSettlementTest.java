package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小组赛与排名赛的赛段级结算:这两条结算路径此前完全没有测试覆盖
 * (settleGroupStage / settleRankStage / settleRankMatch / getRankDetail)。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class GroupRankSettlementTest {

    private static final String DB_PATH = "target/group-rank-settlement.db";

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
    private com.dance.street.game.mapper.TMatchRoundMapper matchRoundMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private com.dance.street.game.mapper.TRefereeMapper refereeMapper;
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

    /** 小组赛导播台判定:按槽位顺序前者胜 */
    private void finishByDirector(Long matchId) {
        // 淘汰赛逐场开始(待开始→进行中);其余赛制开赛时场次已直接进入进行中
        if (StageConstants.MATCH_PENDING.equals(matchMapper.selectById(matchId).getStatus())) {
            matchResultService.startMatch(matchId);
        }
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

    private String groupRule(int groupCount, int advancePerGroup) {
        return "{\"mode\":\"GROUP\",\"group\":{\"groupCount\":" + groupCount
            + ",\"winPoints\":3,\"drawPoints\":1,\"lossPoints\":0,\"advancePerGroup\":"
            + advancePerGroup + "},\"scoring\":{\"matchMode\":\"STANDARD\"}}";
    }

    /** 小组赛判平:双方各记 1 分,用于制造组内同分 */
    private void finishAsDraw(Long matchId) {
        if (StageConstants.MATCH_PENDING.equals(matchMapper.selectById(matchId).getStatus())) {
            matchResultService.startMatch(matchId);
        }
        List<TMatchParticipant> parts = realParticipants(matchId);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        parts.forEach(p -> outcomes.put(p.getCompetitorId(), "DRAW"));
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /**
     * 小组赛晋级线同分:组内全部同分时整组保持待定,由导播台在中间态手动裁决晋级者。
     */
    @Test
    void groupTieOnAdvanceLineWaitsForManualDecision() {
        Long tid = newTournament("小组同分裁决");
        TStageVo stage = newStage(tid, "小组赛", "GROUP", 4, 2, groupRule(2, 1));
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(2, matches.size());
        // 两场全部判平 → 组内两人同分(各 1 分),恰好卡在晋级线上
        for (TMatch m : matches) {
            finishAsDraw(m.getId());
        }

        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(4, countOutcome(stage.getId(), OutcomeStatusEnum.PENDING.getCode()),
            "晋级线同分时不应自动定夺,全部保持待定");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        // 手动裁决:每组各指定 1 人晋级
        Long group1 = realParticipants(matches.get(0).getId()).get(0).getCompetitorId();
        Long group2 = realParticipants(matches.get(1).getId()).get(0).getCompetitorId();
        int advanced = lifecycleService.adjustAdvancement(stage.getId(), List.of(group1, group2));
        assertEquals(2, advanced, "应指定 2 人晋级");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()),
            "未选中的待定者应标记淘汰");
        assertEquals(0, countOutcome(stage.getId(), OutcomeStatusEnum.PENDING.getCode()));
    }

    private Long insertReferee(Long tid, String name) {
        com.dance.street.game.domain.TReferee r = new com.dance.street.game.domain.TReferee();
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

    /** 小组赛:4 人分 2 组,每组单循环 1 场;完成赛段后每组按积分取第 1 名晋级。 */
    @Test
    void groupStageSettlesByPoints() {
        Long tid = newTournament("小组赛结算");
        TStageVo stage = newStage(tid, "小组赛", "GROUP", 4, 2, groupRule(2, 1));
        for (int i = 1; i <= 4; i++) {
            insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i), i);
        }

        lifecycleService.startStage(stage.getId());
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(2, matches.size(), "4 人 2 组应生成 2 场(每组 1 场)");
        assertEquals(2, matches.stream().map(TMatch::getDisplayZone).distinct().count(),
            "两场应分属两个小组");

        for (TMatch m : matches) {
            finishByDirector(m.getId());
        }
        // 场次未全部结算时不能完成赛段
        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "每组晋级 1 人,共 2 人");
        assertEquals(2, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        // 轮次必须跟着场次一起结算:此前"多裁判累计结算"这条路径只置场次,
        // 轮次会残留 GAMING(裁判端看起来这场还在进行中)
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<com.dance.street.game.domain.TMatchRound> rounds = matchRoundMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.dance.street.game.domain.TMatchRound>lambdaQuery()
                .in(com.dance.street.game.domain.TMatchRound::getMatchId, matchIds));
        assertFalse(rounds.isEmpty(), "小组赛场次应各有轮次");
        assertTrue(rounds.stream().allMatch(r -> StageConstants.MATCH_SETTLED.equals(r.getStatus())),
            "场次结算后轮次也应为 SETTLED,实际: "
                + rounds.stream().map(com.dance.street.game.domain.TMatchRound::getStatus).toList());
    }

    /**
     * 排名赛:单圈 4 人,1 名裁判按单维度打分;完成赛段后按总分取前 2 名晋级,
     * 并可通过排名明细查询各维度聚合分。
     */
    @Test
    void rankStageSettlesByAggregatedScore() {
        Long tid = newTournament("排名赛结算");
        Long judge = insertReferee(tid, "裁判A");
        TStageVo stage = newStage(tid, "排名赛", "RANK", 4, 2, rankRule(2));
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
}
