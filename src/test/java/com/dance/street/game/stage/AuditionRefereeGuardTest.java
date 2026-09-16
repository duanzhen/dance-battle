package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选「每圈必须有裁判」守卫与漏配后的补配兜底。
 *
 * <p>海选按圈判:裁判端只显示自己绑到圈上的那场({@code t_match_referee})。
 * 赛段级裁判({@code t_referee_stage})只表示谁参与本赛段,不会自动落到圈上——
 * 圈上没裁判时没人能打分,赛段既结算不了也结束不了,现场只能删赛事重建。
 * 模版建的圈裁判是留空的(由主办方后续在赛段流程里指定),所以这条守卫是必需的。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionRefereeGuardTest {

    private static final String DB_PATH = "target/audition-referee-guard.db";

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
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TRefereeMapper refereeMapper;
    @Autowired
    private TRefereeStageMapper refereeStageMapper;
    @Autowired
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;

    /** 圈上没有裁判时禁止开赛,并指出是哪个圈。 */
    @Test
    void startAuditionIsRejectedWhenCircleHasNoReferee() {
        Long tid = newTournament("圈无裁判");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 1);
        lifecycleService.ensureAuditionCircles(stage.getId());
        putPlayerInCircle(tid, stage, "选手1", "1");

        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.startStage(stage.getId()));
        assertTrue(ex.getMessage().contains("还没有裁判"),
            "应提示圈上缺裁判,实际: " + ex.getMessage());
        assertEquals(StageConstants.STAGE_DRAFT, stageMapper.selectById(stage.getId()).getStatus(),
            "被守卫拦下时赛段不应被开始");
    }

    /** 每圈都配上裁判后即可正常开赛。 */
    @Test
    void startAuditionPassesWhenEveryCircleHasReferee() {
        Long tid = newTournament("圈有裁判");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 1);
        lifecycleService.ensureAuditionCircles(stage.getId());
        putPlayerInCircle(tid, stage, "选手1", "1");
        Long refereeId = insertReferee(tid, "裁判A");
        bindRefereeToCircle(circlesOf(stage.getId()).get(0).getId(), refereeId, tid);

        lifecycleService.startStage(stage.getId());

        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());
    }

    /** 未开赛时可随时改裁判(此时怎么改都不影响任何数据)。 */
    @Test
    void refereesAreEditableBeforeStart() {
        Long tid = newTournament("开赛前可改");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 1);

        lifecycleService.assertRefereesEditable(stage.getId());
    }

    /** 赛段一旦开始,裁判配置即锁定:增减裁判会让同场选手由不同数量的裁判打分。 */
    @Test
    void refereesAreLockedAfterStart() {
        Long tid = newTournament("开赛后锁定");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 1);
        lifecycleService.ensureAuditionCircles(stage.getId());
        bindRefereeToCircle(circlesOf(stage.getId()).get(0).getId(), insertReferee(tid, "裁判A"), tid);
        putPlayerInCircle(tid, stage, "选手1", "1");
        lifecycleService.startStage(stage.getId());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.assertRefereesEditable(stage.getId()));
        assertTrue(ex.getMessage().contains("裁判配置已锁定"),
            "应提示裁判已锁定,实际: " + ex.getMessage());
    }


    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name, int circles, int advanceCount) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        StringBuilder quotas = new StringBuilder("[");
        for (int i = 0; i < circles; i++) {
            quotas.append(i > 0 ? "," : "").append(Math.max(1, advanceCount / circles));
        }
        quotas.append("]");
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":" + circles
            + ",\"advanceCount\":" + advanceCount + ",\"maxScore\":100,"
            + "\"circleAdvanceCounts\":" + quotas + "}");
        return stageService.insertByBo(bo);
    }

    /** 造一名选手并落进第 1 个圈 */
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

    private void setStageStatus(Long stageId, String status) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(status);
        stageMapper.updateById(upd);
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private Long insertReferee(Long tournamentId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private void bindRefereeToStage(Long tournamentId, Long stageId, Long refereeId) {
        TRefereeStage rs = new TRefereeStage();
        rs.setTournamentId(tournamentId);
        rs.setStageId(stageId);
        rs.setRefereeId(refereeId);
        refereeStageMapper.insert(rs);
    }

    private void bindRefereeToCircle(Long matchId, Long refereeId, Long tournamentId) {
        TMatchReferee mr = new TMatchReferee();
        mr.setMatchId(matchId);
        mr.setRefereeId(refereeId);
        mr.setTournamentId(tournamentId);
        matchRefereeMapper.insert(mr);
    }
}
