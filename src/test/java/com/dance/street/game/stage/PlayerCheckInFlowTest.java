package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.CheckInEditBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITPlayerService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到业务层(TPlayerServiceImpl)此前只有 6.3% 覆盖,checkIn / editCheckIn / cancelCheckIn
 * 三个入口全未测试。它们是现场签到页的真实入口,也承载了"落圈由客户端指定"的新契约。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class PlayerCheckInFlowTest {

    private static final String DB_PATH = "target/player-check-in-flow.db";

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
    private TCompetitorMemberMapper competitorMemberMapper;
    @Autowired
    private TMatchRefereeMapper matchRefereeMapper;
    @Autowired
    private TPlayerMapper playerMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITPlayerService playerService;

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    /** 建一个 2 圈海选作为首个赛段,并预建空圈(签到要传目标圈) */
    private TStageVo newAuditionWithCircles(Long tid, int circles) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("海选");
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(2L);
        bo.setIsInitialized(0L);
        StringBuilder quotas = new StringBuilder("[");
        for (int i = 0; i < circles; i++) {
            quotas.append(i > 0 ? "," : "").append(1);
        }
        quotas.append("]");
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":" + circles + ",\"advanceCount\":"
            + circles + ",\"maxScore\":100,\"circleAdvanceCounts\":" + quotas + "}");
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return stage;
    }

    private Long newPlayer(Long tid, String name) {
        TPlayer p = new TPlayer();
        p.setTournamentId(tid);
        p.setName(name);
        playerMapper.insert(p);
        return p.getId();
    }

    private List<TMatch> circleMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    /**
     * 海选按圈判:开赛前每个圈都必须有裁判,否则圈上没人能打分、赛段结束不了。
     * 注意要覆盖全部圈(含还没有选手的圈),守卫是按圈逐个检查的。
     */
    private void bindRefereeToCircles(Long tournamentId, Long stageId) {
        for (TMatch m : circleMatches(stageId)) {
            TMatchReferee mr = new TMatchReferee();
            mr.setMatchId(m.getId());
            mr.setRefereeId(0L);
            mr.setTournamentId(tournamentId);
            matchRefereeMapper.insert(mr);
        }
    }

    private long circleCountOf(Long stageId, Long competitorId) {
        List<Long> matchIds = circleMatches(stageId).stream().map(TMatch::getId).toList();
        return participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
    }

    private TCompetitor competitorOfPlayer(Long playerId) {
        TPlayer p = playerMapper.selectById(playerId);
        return p.getCompetitorId() == null ? null : competitorMapper.selectById(p.getCompetitorId());
    }

    private CheckInBo checkInBo(Long playerId, String type, String number, Long matchId) {
        CheckInBo bo = new CheckInBo();
        bo.setPlayerId(playerId);
        bo.setCheckInType(type);
        bo.setCompetitorNumber(number);
        bo.setMatchId(matchId);
        return bo;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 新建签到:创建参赛单位 + 成员关联,并挂入客户端指定的圈。 */
    @Test
    void createCheckInLandsInSpecifiedCircle() {
        Long tid = newTournament("签到新建");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        List<TMatch> circles = circleMatches(stage.getId());
        Long playerId = newPlayer(tid, "张三");

        TPlayerVo vo = playerService.checkIn(
            checkInBo(playerId, "CREATE", "3", circles.get(1).getId()));

        assertNotNull(vo.getCompetitorId(), "签到后选手应绑定参赛单位");
        TCompetitor c = competitorOfPlayer(playerId);
        assertNotNull(c);
        assertEquals("3", c.getNumber());
        assertEquals(stage.getId(), c.getStageId());
        assertEquals(1, circleCountOf(stage.getId(), c.getId()), "应挂入一个圈");
        assertEquals(c.getId(), participantMapper.selectOne(
                Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, circles.get(1).getId())
                    .last("limit 1"))
            .getCompetitorId(), "应落在客户端指定的第 2 圈");
        assertEquals(1, competitorMemberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, c.getId())
            .eq(TCompetitorMember::getPlayerId, playerId)), "应建立选手-参赛单位关联");
    }

    /** 多圈海选未指定目标圈:后端不再代选,直接拒绝。 */
    @Test
    void createCheckInWithoutCircleIsRejected() {
        Long tid = newTournament("签到未指定圈");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long playerId = newPlayer(tid, "张三");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(playerId, "CREATE", "1", null)));
        assertTrue(ex.getMessage().contains("请指定落圈"),
            "应提示需要指定圈子,实际: " + ex.getMessage());
        assertEquals(0, competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())), "被拒后不应残留参赛单位");
    }

    /** 重复签到、缺号码、无效签到类型都应被明确拒绝。 */
    @Test
    void checkInGuardsAreEnforced() {
        Long tid = newTournament("签到前置校验");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long circle = circleMatches(stage.getId()).get(0).getId();
        Long playerId = newPlayer(tid, "张三");

        RuntimeException noNumber = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(playerId, "CREATE", "  ", circle)));
        assertTrue(noNumber.getMessage().contains("选手号不能为空"));

        RuntimeException badType = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(playerId, "UNKNOWN", "1", circle)));
        assertTrue(badType.getMessage().contains("无效的签到类型"));

        playerService.checkIn(checkInBo(playerId, "CREATE", "1", circle));
        RuntimeException again = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(playerId, "CREATE", "2", circle)));
        assertTrue(again.getMessage().contains("已经签到过"), "实际: " + again.getMessage());
    }

    /** 海选已结束时禁止继续签到(迟到者无法再参与打分与晋级)。 */
    @Test
    void checkInRejectedWhenFirstStageSettled() {
        Long tid = newTournament("已结束不可签到");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long circle = circleMatches(stage.getId()).get(0).getId();
        Long playerId = newPlayer(tid, "张三");

        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> playerService.checkIn(checkInBo(playerId, "CREATE", "1", circle)));
        assertTrue(ex.getMessage().contains("无法继续签到"), "实际: " + ex.getMessage());
    }

    /** JOIN 已存在的参赛单位:只加成员关联;跨赛段的参赛单位不允许加入。 */
    @Test
    void joinExistingCompetitorIsValidated() {
        Long tid = newTournament("加入已有单位");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long circle = circleMatches(stage.getId()).get(0).getId();
        Long firstPlayer = newPlayer(tid, "张三");
        playerService.checkIn(checkInBo(firstPlayer, "CREATE", "1", circle));
        Long competitorId = competitorOfPlayer(firstPlayer).getId();

        Long second = newPlayer(tid, "李四");
        CheckInBo join = checkInBo(second, "JOIN", null, null);
        join.setCompetitorId(competitorId);

        // 个人赛段(members=1)默认不允许再拉人进同一参赛单位
        RuntimeException full = assertThrows(RuntimeException.class, () -> playerService.checkIn(join));
        assertTrue(full.getMessage().contains("最大人数限制"), "实际: " + full.getMessage());

        // 队伍赛段(members=2)才允许加入
        TStage memberUpd = new TStage();
        memberUpd.setId(stage.getId());
        memberUpd.setMembers(2L);
        stageMapper.updateById(memberUpd);
        TPlayerVo vo = playerService.checkIn(join);
        assertEquals(competitorId, vo.getCompetitorId(), "应加入到已有参赛单位");
        assertEquals(2, competitorMemberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, competitorId)));

        // 参赛单位不属于首个赛段 → 拒绝
        TCompetitor other = new TCompetitor();
        other.setTournamentId(tid);
        other.setStageId(-1L);
        other.setType(0L);
        other.setName("别段选手");
        other.setNumber("9");
        competitorMapper.insert(other);
        Long third = newPlayer(tid, "王五");
        CheckInBo badJoin = checkInBo(third, "JOIN", null, null);
        badJoin.setCompetitorId(other.getId());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> playerService.checkIn(badJoin));
        assertTrue(ex.getMessage().contains("只能加入首个赛段"), "实际: " + ex.getMessage());
    }

    /** 编辑签到:改号并按新号码换圈;号码被占用时拒绝。 */
    @Test
    void editCheckInChangesNumberAndCircle() {
        Long tid = newTournament("编辑签到");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        List<TMatch> circles = circleMatches(stage.getId());
        Long playerId = newPlayer(tid, "张三");
        playerService.checkIn(checkInBo(playerId, "CREATE", "1", circles.get(0).getId()));
        Long competitorId = competitorOfPlayer(playerId).getId();

        CheckInEditBo edit = new CheckInEditBo();
        edit.setPlayerId(playerId);
        edit.setCompetitorNumber("4");
        edit.setMatchId(circles.get(1).getId());
        playerService.editCheckIn(edit);

        assertEquals("4", competitorOfPlayer(playerId).getNumber(), "号码应已更新");
        assertEquals(1, circleCountOf(stage.getId(), competitorId), "仍应只在一个圈");
        assertEquals(competitorId, participantMapper.selectOne(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, circles.get(1).getId())
                .last("limit 1"))
            .getCompetitorId(), "应换到目标圈");

        // 占用号码被拒绝
        Long otherPlayer = newPlayer(tid, "李四");
        playerService.checkIn(checkInBo(otherPlayer, "CREATE", "2", circles.get(0).getId()));
        CheckInEditBo conflict = new CheckInEditBo();
        conflict.setPlayerId(playerId);
        conflict.setCompetitorNumber("2");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> playerService.editCheckIn(conflict));
        assertTrue(ex.getMessage().contains("已被占用"), "实际: " + ex.getMessage());
    }

    /** 解除签到:清理参赛单位与成员关联,并解开选手绑定。 */
    @Test
    void cancelCheckInRemovesCompetitorAndUnlinksPlayer() {
        Long tid = newTournament("解除签到");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long circle = circleMatches(stage.getId()).get(0).getId();
        Long playerId = newPlayer(tid, "张三");
        playerService.checkIn(checkInBo(playerId, "CREATE", "1", circle));
        Long competitorId = competitorOfPlayer(playerId).getId();

        playerService.cancelCheckIn(playerId);

        assertNull(playerMapper.selectById(playerId).getCompetitorId(), "选手应解除绑定");
        assertNull(competitorMapper.selectById(competitorId), "最后一名成员解除后参赛单位应删除");
        assertEquals(0, competitorMemberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, competitorId)), "成员关联应清理");
        assertEquals(0, circleCountOf(stage.getId(), competitorId), "应从圈场次移除");

        assertThrows(RuntimeException.class, () -> playerService.cancelCheckIn(playerId),
            "未签到的选手不能再解除");
    }

    /** 赛段开始后签到结果锁定:不能编辑、不能解除。 */
    @Test
    void editAndCancelAreRejectedAfterStageStarted() {
        Long tid = newTournament("开赛后锁定");
        TStageVo stage = newAuditionWithCircles(tid, 2);
        Long circle = circleMatches(stage.getId()).get(0).getId();
        Long playerId = newPlayer(tid, "张三");
        playerService.checkIn(checkInBo(playerId, "CREATE", "1", circle));
        bindRefereeToCircles(tid, stage.getId());
        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        CheckInEditBo edit = new CheckInEditBo();
        edit.setPlayerId(playerId);
        edit.setCompetitorNumber("5");
        RuntimeException editEx = assertThrows(RuntimeException.class, () -> playerService.editCheckIn(edit));
        assertTrue(editEx.getMessage().contains("签到结果已锁定"), "实际: " + editEx.getMessage());

        RuntimeException cancelEx = assertThrows(RuntimeException.class,
            () -> playerService.cancelCheckIn(playerId));
        assertTrue(cancelEx.getMessage().contains("签到结果已锁定"), "实际: " + cancelEx.getMessage());
    }
}
