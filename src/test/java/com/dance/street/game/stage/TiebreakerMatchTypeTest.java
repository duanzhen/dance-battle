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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加赛场次必须落库为显式类型,而不是靠备注文本识别。
 *
 * <p>旧实现把"是不是二海/三海"编码在 {@code t_match.remark} 前缀里,判定散落在
 * 6 处字符串 startsWith,改文案就会静默改变行为。现在 {@code match_type=TIEBREAKER}
 * 是唯一判据(历史行 match_type 为 null 时仍兼容 remark 前缀)。</p>
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

    @Test
    void tiebreakerIsMarkedExplicitlyAndLegacyRemarkStillWorks() {
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

        // 历史数据兼容:match_type 为 null 时仍按 remark 前缀识别
        matchMapper.update(null, Wrappers.<TMatch>lambdaUpdate()
            .eq(TMatch::getId, tb.getId())
            .set(TMatch::getMatchType, null));
        assertTrue(settlementSupport.isTiebreaker(matchMapper.selectById(tb.getId())),
            "老库(match_type 为空)应回退按 remark 前缀识别");
    }

    // ===== 造数据 =====

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
