package com.dance.street.game.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁判端 SSE 推送的"观众集合"口径。
 *
 * <p>回归的事故:海选是<b>按圈判</b>的——主办方把裁判绑在圈上({@code t_match_referee}),
 * 赛段级({@code t_referee_stage})可能是空的。推送此前只查赛段级,空集合直接 return,
 * 于是「开始海选后裁判端没有任何推送,必须手动刷新才出现打分」。现在观众 = 赛段级 ∪ 圈级。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RefereeSseAudienceTest {

    private static final String DB_PATH = "target/referee-sse-audience.db";

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
    private TRefereeMapper refereeMapper;
    @Autowired
    private TRefereeStageMapper refereeStageMapper;
    @Autowired
    private TMatchMapper matchMapper;
    @Autowired
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private RefereeSseNotifier refereeSseNotifier;

    @Test
    void circleBoundRefereeIsIncludedInAudience() {
        Long tid = newTournament("推送观众集合");
        TStageVo stage = newTwoCircleAuditionStage(tid);
        Long stageReferee = insertReferee(tid, "赛段级裁判");
        Long circle1Referee = insertReferee(tid, "一圈裁判");
        Long circle2Referee = insertReferee(tid, "二圈裁判");
        bindRefereeToStage(tid, stage.getId(), stageReferee);

        List<TMatch> circles = circlesOf(stage.getId());
        bindRefereeToCircle(circles.get(0).getId(), circle1Referee, tid);
        bindRefereeToCircle(circles.get(1).getId(), circle2Referee, tid);

        // 赛段级事件:赛段裁判 + 两个圈的裁判都应收到
        List<Long> all = refereeSseNotifier.resolveAudience(stage.getId(), null);
        assertTrue(all.contains(stageReferee), "赛段级裁判应在观众集合内");
        assertTrue(all.contains(circle1Referee), "一圈裁判应在观众集合内(此前恰恰漏了这条)");
        assertTrue(all.contains(circle2Referee), "二圈裁判应在观众集合内");

        // 场次级事件:只发该场的圈裁判 + 赛段裁判,不打扰其他圈
        List<Long> matchScoped = refereeSseNotifier.resolveAudience(stage.getId(), circles.get(0).getId());
        assertTrue(matchScoped.contains(circle1Referee), "该场圈裁判应收到场次事件");
        assertFalse(matchScoped.contains(circle2Referee), "其他圈的裁判不应收到该场次事件");
        assertTrue(matchScoped.contains(stageReferee), "赛段级裁判始终在观众集合内");
    }

    @Test
    void stageWithoutStageRefereeStillHasCircleRefereeAudience() {
        Long tid = newTournament("仅圈级绑定");
        TStageVo stage = newTwoCircleAuditionStage(tid);
        Long circleReferee = insertReferee(tid, "圈裁判");
        bindRefereeToCircle(circlesOf(stage.getId()).get(0).getId(), circleReferee, tid);

        // 赛段级没有任何裁判:旧实现这里返回空集合 → 一条推送都不发
        List<Long> audience = refereeSseNotifier.resolveAudience(stage.getId(), null);
        assertTrue(audience.contains(circleReferee),
            "赛段级为空时,圈级裁判仍必须收到推送(实际: " + audience + ")");
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    /** 2 圈、每圈晋级 1 人(生成器按配置把圈建出来:ZONE-1 / ZONE-2) */
    private TStageVo newTwoCircleAuditionStage(Long tid) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("海选");
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(2L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":2,\"maxScore\":10,"
            + "\"circleAdvanceCounts\":[1,1]}");
        TStageVo stage = stageService.insertByBo(bo);
        // 直接建两个圈场次(等价于 ensure-circle-slots)
        insertCircle(tid, stage.getId(), "ZONE-1", 0L);
        insertCircle(tid, stage.getId(), "ZONE-2", 1L);
        return stage;
    }

    private void insertCircle(Long tid, Long stageId, String zone, Long row) {
        TMatch m = new TMatch();
        m.setTournamentId(tid);
        m.setStageId(stageId);
        m.setName("海选赛-" + zone);
        m.setDisplayZone(zone);
        m.setDisplayRow(row);
        m.setStatus(StageConstants.MATCH_PENDING);
        m.setMatchMode("VOTING");
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);
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

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }
}
