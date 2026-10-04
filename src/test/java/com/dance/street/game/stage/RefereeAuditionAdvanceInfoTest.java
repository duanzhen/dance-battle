package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.IRefereeMatchService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 裁判端海选「本圈晋级几人 / 本赛段共晋级几人」在打分前后都要可见。
 *
 * <p>现场问题:裁判一开始打分,这两个数字就消失了(前端 `v-if="advanceCount > 0"` 直接隐藏整行)。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RefereeAuditionAdvanceInfoTest {

    private static final String DB_PATH = "target/referee-audition-advance-info.db";

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
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private IRefereeMatchService refereeMatchService;

    @Test
    void advanceInfoStaysVisibleAfterScoring() {
        TTournament t = new TTournament();
        t.setName("裁判端晋级人数");
        tournamentMapper.insert(t);
        Long tid = t.getId();

        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("海选");
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":1,"
            + "\"maxScore\":10,\"circleAdvanceCounts\":[1]}");
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        TMatch circle = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())).get(0);

        TReferee ref = new TReferee();
        ref.setTournamentId(tid);
        ref.setName("裁判A");
        refereeMapper.insert(ref);
        TMatchReferee mr = new TMatchReferee();
        mr.setMatchId(circle.getId());
        mr.setRefereeId(ref.getId());
        mr.setTournamentId(tid);
        matchRefereeMapper.insert(mr);

        Long cid1 = insertCompetitor(tid, stage, "甲", "1", circle.getId());
        Long cid2 = insertCompetitor(tid, stage, "乙", "2", circle.getId());
        Long cid3 = insertCompetitor(tid, stage, "丙", "3", circle.getId());
        lifecycleService.startStage(stage.getId());

        // 打分前:本圈 1 人、本赛段 1 人
        RefereeMatchVo before = refereeMatchService.myMatch(tid, ref.getId(), "裁判A",
            stage.getId(), circle.getId());
        assertEquals(1, before.getAdvanceCount(), "打分前本圈晋级人数应可见");
        assertEquals(1, before.getStageAdvanceCount(), "打分前本赛段晋级人数应可见");

        // 打一个人的分
        submitScore(circle.getId(), ref.getId(), cid1, "9");

        // 打分后:两个数字仍应可见
        RefereeMatchVo after = refereeMatchService.myMatch(tid, ref.getId(), "裁判A",
            stage.getId(), circle.getId());
        assertEquals(1, after.getAdvanceCount(), "打分后本圈晋级人数仍应可见");
        assertEquals(1, after.getStageAdvanceCount(), "打分后本赛段晋级人数仍应可见");
    }

    private Long insertCompetitor(Long tid, TStageVo stage, String name, String number, Long matchId) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), matchId);
        return c.getId();
    }

    private void submitScore(Long matchId, Long refereeId, Long competitorId, String value) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        bo.setFinalizeStageIfComplete(false);
        ScoreEntryBo se = new ScoreEntryBo();
        se.setCompetitorId(competitorId);
        se.setDimension("MAIN");
        se.setAction("SCORE");
        se.setScore(new BigDecimal(value));
        bo.setScores(List.of(se));
        matchResultService.submitResult(bo);
    }
}
