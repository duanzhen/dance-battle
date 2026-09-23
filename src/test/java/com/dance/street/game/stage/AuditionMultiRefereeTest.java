package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选多裁判:必须「本场(本圈)绑定的每一名裁判都打过分」才算判完。
 *
 * <p>回归的事故:加赛(二海)的「全员打分完成即自动结算」只看「每名选手是否有一条分」,
 * 不看是谁打的。多裁判赛场只要第一个裁判把同分选手都判完,就当场自动结算——
 * 其余裁判还没判的分整段作废,而总分是跨裁判求和的,晋级线因此可能判错;
 * 分数偏低又更容易再并列,连环生成三海/四海直到超过上限报错。</p>
 *
 * <p>另一条:多圈海选各圈裁判不同({@code circleRefereeIds}),「应到几名裁判」必须按
 * 本场(本圈)单独解析。若拿全赛段的裁判集合来比,某圈的选手永远等不到别圈的裁判,赛段卡死。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionMultiRefereeTest {

    private static final String DB_PATH = "target/audition-multi-referee.db";

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
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;

    /**
     * 核心回归:一圈两名裁判。第一名裁判把同分选手都判完,加赛不得自动结算;
     * 第二名裁判补齐后,分数才完整(跨裁判求和),此时才自动结算。
     */
    @Test
    void tiebreakerDoesNotAutoSettleUntilEveryBoundRefereeScores() {
        Long tid = newTournament("加赛多裁判");
        TReferee refereeA = newReferee(tid, "裁判A");
        TReferee refereeB = newReferee(tid, "裁判B");
        TStageVo stage = newAuditionStage(tid, "海选", 1, new long[]{1}, null);
        lifecycleService.ensureAuditionCircles(stage.getId());
        TMatch circle = circlesOf(stage.getId()).get(0);
        bindRefereeToCircle(circle.getId(), refereeA.getId(), tid);
        bindRefereeToCircle(circle.getId(), refereeB.getId(), tid);
        putPlayers(tid, stage, circle.getId(), 3);
        lifecycleService.startStage(stage.getId());

        // 两名裁判都判完正式圈:前两名同分争 1 个名额 → 二海(需两人都被两名裁判判过才算判完)
        scoreAll(circle.getId(), refereeA.getId(), List.of(bd("5"), bd("5"), bd("1")));
        scoreAll(circle.getId(), refereeB.getId(), List.of(bd("5"), bd("5"), bd("1")));
        lifecycleService.completeStage(stage.getId());

        TMatch tiebreak = tiebreakerOf(stage.getId());
        assertEquals("ZONE-1", tiebreak.getDisplayZone());
        assertEquals(2, participantsOf(tiebreak.getId()).size(), "二海应只有同分的两位选手");

        // 1) 先到的裁判A判完全部同分选手:不得自动结算(旧实现在这里就会结束)
        scoreAll(tiebreak.getId(), refereeA.getId(), List.of(bd("9"), bd("8")));
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(tiebreak.getId()).getStatus(),
            "只有一名裁判打分不得自动结算加赛");
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "加赛未判完赛段应保持进行中");
        List<TMatchParticipant> halfScored = participantsOf(tiebreak.getId());
        assertEquals(0, bd("9").compareTo(halfScored.get(0).getScoreValue()),
            "此时只应累计到裁判A一个人的分");

        // 2) 裁判B补齐:分数跨裁判求和后完整,自动结算
        scoreAll(tiebreak.getId(), refereeB.getId(), List.of(bd("9"), bd("8")));
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(tiebreak.getId()).getStatus(),
            "全部绑定裁判判完后加赛应自动结算");
        List<TMatchParticipant> fullyScored = participantsOf(tiebreak.getId());
        assertEquals(0, bd("18").compareTo(fullyScored.get(0).getScoreValue()),
            "总分应包含两名裁判的分");
    }

    /**
     * 多圈不同裁判:某圈的加赛只等本圈裁判,不等别圈裁判——否则要么被别圈裁判卡死,
     * 要么把只有一名本圈裁判打的分当完整分结算。
     */
    @Test
    void multiCircleTiebreakerRequiresItsOwnCircleReferees() {
        Long tid = newTournament("多圈不同裁判加赛");
        TReferee z1a = newReferee(tid, "圈1裁判A");
        TReferee z1b = newReferee(tid, "圈1裁判B");
        TReferee z2a = newReferee(tid, "圈2裁判A");
        TReferee z2b = newReferee(tid, "圈2裁判B");
        TStageVo stage = newAuditionStage(tid, "海选", 2, new long[]{1, 1},
            "[[\"" + z1a.getId() + "\",\"" + z1b.getId() + "\"],[\""
                + z2a.getId() + "\",\"" + z2b.getId() + "\"]]");
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circlesOf(stage.getId());
        assertEquals(2, circles.size());
        TMatch circle1 = circles.get(0);
        TMatch circle2 = circles.get(1);
        assertEquals("ZONE-1", circle1.getDisplayZone());
        assertEquals("ZONE-2", circle2.getDisplayZone());
        putPlayers(tid, stage, circle1.getId(), 2);
        putPlayers(tid, stage, circle2.getId(), 3);
        lifecycleService.startStage(stage.getId());

        // 圈1:两名裁判各打一遍,分数不并列 → 正常结算
        scoreAll(circle1.getId(), z1a.getId(), List.of(bd("5"), bd("3")));
        scoreAll(circle1.getId(), z1b.getId(), List.of(bd("4"), bd("2")));
        // 圈2:两名裁判各打一遍,前两名同分 → 本圈开二海
        scoreAll(circle2.getId(), z2a.getId(), List.of(bd("5"), bd("5"), bd("1")));
        scoreAll(circle2.getId(), z2b.getId(), List.of(bd("5"), bd("5"), bd("1")));
        lifecycleService.completeStage(stage.getId());

        TMatch tiebreak = tiebreakerOf(stage.getId());
        assertEquals("ZONE-2", tiebreak.getDisplayZone(), "加赛应归属发生同分的第 2 圈");
        Set<Long> bound = new LinkedHashSet<>(refereeIdsOf(tiebreak.getId()));
        assertEquals(Set.of(z2a.getId(), z2b.getId()), bound,
            "加赛应只继承本圈(第 2 圈)的裁判,而不是全赛段裁判");

        // 只有圈2的一名裁判判完:不得结算(要求的裁判数按本圈算,且别圈裁判的分不算数)
        scoreAll(tiebreak.getId(), z2a.getId(), List.of(bd("9"), bd("7")));
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(tiebreak.getId()).getStatus(),
            "本圈还有裁判没判,不得自动结算");

        // 圈2第二名裁判补齐 → 结算
        scoreAll(tiebreak.getId(), z2b.getId(), List.of(bd("9"), bd("7")));
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(tiebreak.getId()).getStatus(),
            "本圈两名裁判判完后应自动结算");

        // 全程没有要求圈1裁判参与,证明要求是按本圈解析的
        assertTrue(StageConstants.MATCH_SETTLED.equals(matchMapper.selectById(circle1.getId()).getStatus()));
    }

    /** 正式圈(非加赛)也按同一口径拦:缺任一裁判的分不许结束赛段。 */
    @Test
    void completeStageIsBlockedUntilEveryBoundRefereeScores() {
        Long tid = newTournament("正式圈多裁判守卫");
        TReferee refereeA = newReferee(tid, "裁判A");
        TReferee refereeB = newReferee(tid, "裁判B");
        TStageVo stage = newAuditionStage(tid, "海选", 1, new long[]{1}, null);
        lifecycleService.ensureAuditionCircles(stage.getId());
        TMatch circle = circlesOf(stage.getId()).get(0);
        bindRefereeToCircle(circle.getId(), refereeA.getId(), tid);
        bindRefereeToCircle(circle.getId(), refereeB.getId(), tid);
        putPlayers(tid, stage, circle.getId(), 2);
        lifecycleService.startStage(stage.getId());

        // 只有裁判A判完:完成赛段应被拦下,赛段保持进行中
        scoreAll(circle.getId(), refereeA.getId(), List.of(bd("5"), bd("3")));
        StageCompleteVo blocked = lifecycleService.completeStage(stage.getId());
        assertFalse(blocked.getCompleted(), "还有裁判没打分不应结束赛段");
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "被拦下时赛段应保持进行中");

        // 裁判B补齐后即可正常结束
        scoreAll(circle.getId(), refereeB.getId(), List.of(bd("4"), bd("2")));
        StageCompleteVo settled = lifecycleService.completeStage(stage.getId());
        assertTrue(settled.getCompleted(), "全部绑定裁判判完后应能结束赛段");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
    }

    // ===== 造数据 =====

    private BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TReferee newReferee(Long tournamentId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName(name);
        refereeMapper.insert(r);
        return r;
    }

    private TStageVo newAuditionStage(Long tid, String name, int circles, long[] quotas, String circleRefereeIdsJson) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        long total = 0L;
        for (long q : quotas) {
            total += q;
        }
        bo.setTeamCountEnd(total);
        bo.setIsInitialized(0L);
        StringBuilder quotaJson = new StringBuilder("[");
        for (int i = 0; i < quotas.length; i++) {
            quotaJson.append(i > 0 ? "," : "").append(quotas[i]);
        }
        quotaJson.append("]");
        String rule = "{\"mode\":\"AUDITION\",\"circles\":" + circles + ",\"advanceCount\":" + total
            + ",\"maxScore\":100,\"circleAdvanceCounts\":" + quotaJson;
        if (circleRefereeIdsJson != null) {
            rule = rule + ",\"circleRefereeIds\":" + circleRefereeIdsJson;
        }
        rule = rule + "}";
        bo.setRuleConfig(rule);
        return stageService.insertByBo(bo);
    }

    /** 造 n 名选手并落进指定圈 */
    private void putPlayers(Long tid, TStageVo stage, Long matchId, int n) {
        int existing = participantsOf(matchId).size();
        for (int i = 1; i <= n; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(stage.getId());
            c.setType(0L);
            c.setName("选手" + (existing + i));
            c.setNumber(String.valueOf(existing + i));
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
            lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), matchId);
        }
    }

    /** 某裁判按上场顺序给本场全部选手打分(一次提交) */
    private void scoreAll(Long matchId, Long refereeId, List<BigDecimal> values) {
        ensureMatchGaming(matchId);
        List<TMatchParticipant> parts = participantsOf(matchId);
        assertEquals(values.size(), parts.size(), "打分人数应与参赛人数一致");
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setDimension("MAIN");
            se.setAction("SCORE");
            se.setScore(values.get(i));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    private void ensureMatchGaming(Long matchId) {
        TMatch m = matchMapper.selectById(matchId);
        if (m != null && !StageConstants.MATCH_GAMING.equals(m.getStatus())) {
            matchResultService.startMatch(matchId);
        }
    }

    private void bindRefereeToCircle(Long matchId, Long refereeId, Long tournamentId) {
        TMatchReferee mr = new TMatchReferee();
        mr.setMatchId(matchId);
        mr.setRefereeId(refereeId);
        mr.setTournamentId(tournamentId);
        matchRefereeMapper.insert(mr);
    }

    private List<Long> refereeIdsOf(Long matchId) {
        return matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, matchId))
            .stream().map(TMatchReferee::getRefereeId).toList();
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private TMatch tiebreakerOf(Long stageId) {
        List<TMatch> list = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER)
            .orderByAsc(TMatch::getId));
        assertEquals(1, list.size(), "应恰好有一场加赛");
        return list.get(0);
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return new ArrayList<>(participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex)));
    }
}
