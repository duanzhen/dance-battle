package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.settle.AuditionAdvanceInfoSupport;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加赛场次必须落库为显式类型,而不是靠备注文本识别。
 *
 * <p>旧实现把"是不是二海/三海"编码在 {@code t_match.remark} 前缀里,判定散落在
 * 6 处字符串 startsWith,改文案就会静默改变行为。现在 {@code match_type=TIEBREAKER}
 * 是唯一判据。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TiebreakerMatchTypeTest {

    private static final String DB_PATH = "target/tiebreaker-match-type.db";

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
    private TRoundScoreMapper roundScoreMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TRefereeMapper refereeMapper;
    @Autowired
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private SettlementSupport settlementSupport;
    @Autowired
    private AuditionAdvanceInfoSupport auditionAdvanceInfoSupport;

    @Test
    void tiebreakerIsMarkedExplicitlyByMatchType() {
        Long tid = newTournament("加赛类型");
        TStageVo stage = newAuditionStage(tid, "海选", 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 4; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        // 4 人取 2 人,前三名同分 → 晋级线被同分横跨,结算时创建二海(3 人争 2 个名额)
        List<TMatchParticipant> parts = participantsOf(circleId);
        score(circleId, parts.get(0).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(2).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(3).getCompetitorId(), new BigDecimal("4"));
        lifecycleService.completeStage(stage.getId());

        List<TMatch> tiebreakers = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER));
        assertEquals(1, tiebreakers.size(), "晋级线同分应创建 1 场二海,并显式标记 match_type");
        TMatch tb = tiebreakers.get(0);
        assertEquals(circleId, tb.getParentMatchId(), "加赛应记录来源场次");
        assertTrue(settlementSupport.isTiebreaker(tb), "显式类型应被判定为加赛");

        // 普通场次不会被误判为加赛
        assertFalse(settlementSupport.isTiebreaker(circlesOf(stage.getId()).get(0)),
            "正式圈不是加赛");

        // 备注文本不再是判据:正式圈的 remark 即使写成加赛格式,也不按加赛处理
        TMatch circle = circlesOf(stage.getId()).get(0);
        matchMapper.update(null, Wrappers.<TMatch>lambdaUpdate()
            .eq(TMatch::getId, circle.getId())
            .set(TMatch::getRemark, "同分加赛,晋级名额,3人"));
        assertFalse(settlementSupport.isTiebreaker(matchMapper.selectById(circle.getId())),
            "加赛判定只看 match_type,不看备注文本");
    }

    /**
     * 裁判端 / MC 导播台要显示的「晋级人数」必须与结算一致:
     * 正式圈 = 本圈晋级名额;加赛(二海/三海…) = 本圈<b>剩余</b>名额。
     *
     * <p>回归:此前两端都没有这个数,MC 只能靠嘴问"这一圈进几个"。</p>
     */
    @Test
    void advanceCountMatchesSettlementSemantics() {
        Long tid = newTournament("晋级人数展示");
        TStageVo stage = newAuditionStage(tid, "海选", 3);
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        TMatch circle = circlesOf(stage.getId()).get(0);
        bindRefereeToCircle(circle.getId(), refereeId, tid);
        for (int i = 1; i <= 5; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        TStage fresh = stageMapper.selectById(stage.getId());
        assertEquals(3, auditionAdvanceInfoSupport.stageAdvanceCount(fresh), "赛段共晋级 3 人");
        assertEquals(3, auditionAdvanceInfoSupport.matchAdvanceCount(fresh, circle),
            "正式圈 = 本圈晋级名额(3)");

        // 3 个名额:9、8 分直接晋级,后三名 7 分同分争最后 1 个名额
        List<TMatchParticipant> parts = participantsOf(circle.getId());
        score(circle.getId(), parts.get(0).getCompetitorId(), new BigDecimal("9"));
        score(circle.getId(), parts.get(1).getCompetitorId(), new BigDecimal("8"));
        for (int i = 2; i < 5; i++) {
            score(circle.getId(), parts.get(i).getCompetitorId(), new BigDecimal("7"));
        }
        lifecycleService.completeStage(stage.getId());

        List<TMatch> tiebreakers = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER));
        assertEquals(1, tiebreakers.size(), "应创建 1 场二海");
        TMatch tb = tiebreakers.get(0);
        TStage settled = stageMapper.selectById(stage.getId());
        assertEquals(1, auditionAdvanceInfoSupport.matchAdvanceCount(settled, tb),
            "加赛只争本圈剩余名额(3-2=1)");
        assertEquals("二海", auditionAdvanceInfoSupport.tiebreakerRoundName(tb),
            "加赛轮次名应为人话标签");
        assertNull(auditionAdvanceInfoSupport.tiebreakerRoundName(circle),
            "正式圈不是加赛,轮次名为空");
    }

    /**
     * 加赛场次同样按「逐选手轮次」建:3 人加赛 = 3 个轮次,每个轮次绑定一名选手,分数落在自己的轮次上。
     *
     * <p>回归的事故:加赛只建了 1 个轮次(无选手绑定),裁判端「第N轮」只显示一轮,
     * 3 个人的加赛看起来像只判了 1 个人;打分也只能退化成"单轮多人共享",
     * 与正式圈的逐选手轮次是两套口径。</p>
     */
    @Test
    void tiebreakerHasOneRoundPerTiedCompetitor() {
        Long tid = newTournament("加赛轮次");
        TStageVo stage = newAuditionStage(tid, "海选", 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 4; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        // 4 人取 2 人,前三名同分 → 晋级线被同分横跨,结算创建 3 人的二海
        List<TMatchParticipant> parts = participantsOf(circleId);
        score(circleId, parts.get(0).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(2).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(3).getCompetitorId(), new BigDecimal("4"));
        lifecycleService.completeStage(stage.getId());

        TMatch tb = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER))
            .get(0);
        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, tb.getId())
            .orderByAsc(TMatchRound::getRoundSequence));
        assertEquals(1, rounds.size(), "加赛场次应只有一个回合(round),选手由 participant 承载");
        List<TMatchParticipant> tbParts = participantsOf(tb.getId());
        assertEquals(3, tbParts.size(), "3 人加赛应有 3 个参赛位");
        assertEquals(3, tbParts.stream().map(TMatchParticipant::getCompetitorId).distinct().count(),
            "参赛位与同分选手一一对应");

        // 打分写进 (回合, 选手):3 人的分落在同一回合,按 competitor_id 区分到人
        Long roundId = rounds.get(0).getId();
        Long firstTied = tbParts.get(0).getCompetitorId();
        score(tb.getId(), firstTied, new BigDecimal("9.5"));
        List<TRoundScore> scoreRows = roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
            .eq(TRoundScore::getRoundId, roundId));
        assertTrue(scoreRows.stream().anyMatch(r ->
                Objects.equals(r.getRoundId(), roundId) && Objects.equals(r.getCompetitorId(), firstTied)),
            "选手的加赛分应落在本场回合上(按 competitor_id 区分)");
    }

    // ===== 造数据 =====

    /**
     * 出排名时(赛段结算完成那一刻)按明细重算显示用总分,且必须"按场"分开:
     * 原圈行的总分 = 原圈明细和(不掺加赛分);加赛行的总分 = 加赛明细和。
     */
    @Test
    void settlementRecomputesTotalsPerMatchAcrossTiebreakers() {
        Long tid = newTournament("加赛重算总分");
        TStageVo stage = newAuditionStage(tid, "海选", 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 4; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        List<TMatchParticipant> parts = participantsOf(circleId);
        Long a = parts.get(0).getCompetitorId();
        Long b = parts.get(1).getCompetitorId();
        Long c = parts.get(2).getCompetitorId();
        Long d = parts.get(3).getCompetitorId();
        score(circleId, a, new BigDecimal("9"));
        score(circleId, b, new BigDecimal("9"));
        score(circleId, c, new BigDecimal("9"));
        score(circleId, d, new BigDecimal("4"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getTiebreaker(), "前三名同分应产生二海");

        TMatch tb = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER))
            .get(0);
        List<TMatchParticipant> tbParts = participantsOf(tb.getId());
        assertEquals(3, tbParts.size(), "三人加赛");
        score(tb.getId(), tbParts.get(0).getCompetitorId(), new BigDecimal("9.5"));
        score(tb.getId(), tbParts.get(1).getCompetitorId(), new BigDecimal("9"));
        score(tb.getId(), tbParts.get(2).getCompetitorId(), new BigDecimal("8"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(), "加赛判完应能结束赛段");

        // 原圈行:总分 = 原圈分,不掺加赛分
        assertEquals(0, new BigDecimal("9").compareTo(scoreValueOf(circleId, a)), "原圈 A 应为 9");
        assertEquals(0, new BigDecimal("9").compareTo(scoreValueOf(circleId, b)), "原圈 B 应为 9");
        assertEquals(0, new BigDecimal("9").compareTo(scoreValueOf(circleId, c)), "原圈 C 应为 9");
        assertEquals(0, new BigDecimal("4").compareTo(scoreValueOf(circleId, d)), "原圈 D 应为 4");
        // 加赛行:总分 = 加赛分
        assertEquals(0, new BigDecimal("9.5").compareTo(scoreValueOf(tb.getId(), tbParts.get(0).getCompetitorId())));
        assertEquals(0, new BigDecimal("9").compareTo(scoreValueOf(tb.getId(), tbParts.get(1).getCompetitorId())));
        assertEquals(0, new BigDecimal("8").compareTo(scoreValueOf(tb.getId(), tbParts.get(2).getCompetitorId())));
    }

    private BigDecimal scoreValueOf(Long matchId, Long competitorId) {
        return participantsOf(matchId).stream()
            .filter(p -> Objects.equals(p.getCompetitorId(), competitorId))
            .map(TMatchParticipant::getScoreValue)
            .findFirst().orElse(null);
    }

    /**
     * 手动指定模式的加赛:不打分,由导播指定谁晋级;晋级排序按号码牌升序。
     */
    @Test
    void manualTiebreakDesignatesAdvancersByNumber() {
        Long tid = newTournament("手动加赛");
        TStageVo stage = newAuditionStage(tid, "海选", 2, "MANUAL");
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 4; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        List<TMatchParticipant> parts = participantsOf(circleId);
        Long a = parts.get(0).getCompetitorId();
        Long b = parts.get(1).getCompetitorId();
        Long c = parts.get(2).getCompetitorId();
        Long d = parts.get(3).getCompetitorId();
        score(circleId, a, new BigDecimal("9"));
        score(circleId, b, new BigDecimal("9"));
        score(circleId, c, new BigDecimal("9"));
        score(circleId, d, new BigDecimal("4"));
        // 前三名同分争 2 个名额 → 生成二海(手动模式:不能直接结束,要等指定)
        assertFalse(lifecycleService.completeStage(stage.getId()).getCompleted(), "生成二海后不能直接结束");
        assertFalse(lifecycleService.completeStage(stage.getId()).getCompleted(), "未指定晋级人员不能结束");

        TMatch tb = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER))
            .get(0);
        List<TMatchParticipant> tbParts = participantsOf(tb.getId());
        assertEquals(3, tbParts.size(), "三人同分进二海");
        List<Long> cids = tbParts.stream().map(TMatchParticipant::getCompetitorId).toList();

        // 指定第 1、2 号(乱序传入)晋级 2 人;服务端按号码牌升序规范
        matchResultService.designateAuditionTiebreakAdvance(tb.getId(), List.of(cids.get(1), cids.get(0)));
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(), "指定后应能结束赛段");

        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), competitorMapper.selectById(cids.get(0)).getOutcomeStatus());
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), competitorMapper.selectById(cids.get(1)).getOutcomeStatus());
        assertEquals(OutcomeStatusEnum.ELIMINATED.getCode(), competitorMapper.selectById(cids.get(2)).getOutcomeStatus());
        // 原圈名次:晋级者按号码牌升序占名额段(1、2),淘汰者排其后
        assertEquals(1L, rankInMatchOf(circleId, cids.get(0)).longValue());
        assertEquals(2L, rankInMatchOf(circleId, cids.get(1)).longValue());
        assertEquals(3L, rankInMatchOf(circleId, cids.get(2)).longValue());
    }

    private Long rankInMatchOf(Long matchId, Long competitorId) {
        return participantsOf(matchId).stream()
            .filter(p -> Objects.equals(p.getCompetitorId(), competitorId))
            .map(TMatchParticipant::getRankInMatch)
            .findFirst().orElse(null);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name, int advanceCount) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":" + advanceCount
            + ",\"maxScore\":10,\"circleAdvanceCounts\":[" + advanceCount + "]}");
        return stageService.insertByBo(bo);
    }

    /** 与 {@link #newAuditionStage(Long, String, int)} 同,但带加赛晋级方式(SCORE/MANUAL)。 */
    private TStageVo newAuditionStage(Long tid, String name, int advanceCount, String tiebreakMode) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        String extra = tiebreakMode == null ? "" : ",\"tiebreakMode\":\"" + tiebreakMode + "\"";
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":" + advanceCount
            + ",\"maxScore\":10,\"circleAdvanceCounts\":[" + advanceCount + "]" + extra + "}");
        return stageService.insertByBo(bo);
    }

    private void score(Long matchId, Long competitorId, BigDecimal value) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(null);
        bo.setFinalizeStageIfComplete(false);
        ScoreEntryBo entry = new ScoreEntryBo();
        entry.setCompetitorId(competitorId);
        entry.setDimension("MAIN");
        entry.setAction("SCORE");
        entry.setScore(value);
        bo.setScores(List.of(entry));
        matchResultService.submitResult(bo);
    }

    private Long insertReferee(Long tournamentId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private void bindRefereeToCircle(Long matchId, Long refereeId, Long tournamentId) {
        TMatchReferee mr = new TMatchReferee();
        mr.setMatchId(matchId);
        mr.setRefereeId(refereeId);
        mr.setTournamentId(tournamentId);
        matchRefereeMapper.insert(mr);
    }

    private void putPlayerInCircle(Long tid, TStageVo stage, String name, String number) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(),
            circlesOf(stage.getId()).get(0).getId());
    }

    private List<TMatch> circlesOf(Long stageId) {
        // 正式圈 = 非加赛场次(老数据 match_type 为空,统一用 isTiebreaker 判定)
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId))
            .stream()
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }
}
