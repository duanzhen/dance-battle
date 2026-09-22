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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选没判完不许结束赛段。
 *
 * <p>回归的事故:海选有人一条分都没打,点「完成赛段」照样结算,没打分的人被当 0 分淘汰;
 * 旧的守卫只拦二海(同分加赛),正式圈整圈没判是不拦的。</p>
 *
 * <p>口径:0 分与「没打分」是两回事——裁判打了 0 分算已判(0 分不参与晋级),
 * 已标记退赛的选手不算未判。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionCompleteGuardTest {

    private static final String DB_PATH = "target/audition-complete-guard.db";

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

    @Test
    void completeIsBlockedWhileSomeCompetitorHasNoScore() {
        Long tid = newTournament("海选未判完");
        TStageVo stage = startAuditionWithThreePlayers(tid, "海选");

        // 只给其中两位打分,第三位一条分都没有
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        List<TMatchParticipant> parts = participantsOf(circleId);
        score(circleId, parts.get(0).getCompetitorId(), new BigDecimal("9.5"));
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("8.0"));

        // 1) 点完成赛段会被拦下:赛段保持进行中,不能把没打分的人当 0 分淘汰
        StageCompleteVo result = lifecycleService.completeStage(stage.getId());
        assertFalse(result.getCompleted(), "未判完不应结束");
        assertTrue(result.getMessage().contains("选手3"),
            "原因里应点名未打分的选手,实际: " + result.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "被拦下时赛段应保持进行中");

        // 2) 补齐最后一位后即可正常结束
        score(circleId, parts.get(2).getCompetitorId(), new BigDecimal("7.0"));
        StageCompleteVo settled = lifecycleService.completeStage(stage.getId());
        assertTrue(settled.getCompleted(), "全员判完后应能结束");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
    }

    @Test
    void zeroScoreAndWithdrawnCountAsJudged() {
        Long tid = newTournament("0分与退赛");
        TStageVo stage = startAuditionWithThreePlayers(tid, "海选");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        List<TMatchParticipant> parts = participantsOf(circleId);

        // 0 分是"判完了"(只是不参与晋级);另一位标记退赛不算未判
        score(circleId, parts.get(0).getCompetitorId(), BigDecimal.ZERO);
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("8.5"));
        TCompetitor withdrawn = new TCompetitor();
        withdrawn.setId(parts.get(2).getCompetitorId());
        withdrawn.setOutcomeStatus(OutcomeStatusEnum.WITHDRAWN.getCode());
        competitorMapper.updateById(withdrawn);

        StageCompleteVo result = lifecycleService.completeStage(stage.getId());
        assertTrue(result.getCompleted(), "打 0 分与标记退赛都不应算未判完");
    }

    /**
     * 晋级线同分时「完成赛段」不是完成,而是当场生成加赛:返回值必须显式标记 tiebreaker,
     * 前端据此弹「需要加赛」——否则导播只看到赛段还是进行中,不知道要拉谁回来打分。
     */
    @Test
    void completeReportsTiebreakerAsPendingWithReason() {
        Long tid = newTournament("海选加赛提示");
        TStageVo stage = startAuditionWithThreePlayers(tid, "海选");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        List<TMatchParticipant> parts = participantsOf(circleId);
        // 前两名同分争 1 个晋级名额 → 加赛;第三名 4 分被淘汰
        score(circleId, parts.get(0).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(2).getCompetitorId(), new BigDecimal("4"));

        // 1) 首次点完成:当场生成加赛,返回值标记 tiebreaker 并给出可直接展示的原因
        StageCompleteVo result = lifecycleService.completeStage(stage.getId());
        assertFalse(result.getCompleted(), "有加赛未判完不应算结束");
        assertTrue(result.getTiebreaker(), "同分加赛必须显式标记,前端才能弹「需要加赛」");
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains("需要加赛"),
            "原因应说明需要加赛,实际: " + result.getMessage());
        assertTrue(result.getMessage().contains("二海"),
            "原因应说明是几海,实际: " + result.getMessage());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus(),
            "加赛未判完时赛段应保持进行中");

        // 2) 加赛还没打分就再点:仍是加赛口径(而不是笼统的"还有选手未打分")
        StageCompleteVo again = lifecycleService.completeStage(stage.getId());
        assertFalse(again.getCompleted());
        assertTrue(again.getTiebreaker(), "加赛未判完再次点击同样应标记 tiebreaker");
        assertTrue(again.getMessage().contains("需要加赛"),
            "原因应继续指向加赛,实际: " + again.getMessage());

        // 3) 裁判打完加赛(全员有分即自动结算)后再点:正常结束
        List<TMatch> tiebreakers = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER));
        assertEquals(1, tiebreakers.size(), "晋级线同分应创建 1 场二海");
        List<TMatchParticipant> tbParts = participantsOf(tiebreakers.get(0).getId());
        assertEquals(2, tbParts.size(), "二海应只有同分的两位选手参加");
        score(tiebreakers.get(0).getId(), tbParts.get(0).getCompetitorId(), new BigDecimal("9.8"));
        score(tiebreakers.get(0).getId(), tbParts.get(1).getCompetitorId(), new BigDecimal("9.1"));

        StageCompleteVo settled = lifecycleService.completeStage(stage.getId());
        assertTrue(settled.getCompleted(), "加赛判完后应能结束赛段");
        assertFalse(settled.getTiebreaker(), "已结束时不应再标记加赛");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    /** 1 个圈、晋级 1 人、3 名选手、1 名裁判绑在圈上,并开赛 */
    private TStageVo startAuditionWithThreePlayers(Long tid, String name) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":1,\"maxScore\":100,"
            + "\"circleAdvanceCounts\":[1]}");
        TStageVo stage = stageService.insertByBo(bo);

        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        bindRefereeToCircle(circlesOf(stage.getId()).get(0).getId(), refereeId, tid);
        for (int i = 1; i <= 3; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());
        return stage;
    }

    /** 裁判给某位选手打分(海选按圈判:分数记在该圈场次的轮次上) */
    private void score(Long matchId, Long competitorId, BigDecimal value) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(null);
        // 海选是"多裁判累计",提交只累计不结算;显式关掉自动收尾,让本用例自己决定何时结束赛段
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

    private Long putPlayerInCircle(Long tid, TStageVo stage, String name, String number) {
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
        return c.getId();
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return new ArrayList<>(participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex)));
    }
}
