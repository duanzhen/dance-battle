package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到流程:预建空圈 → 逐个签到落圈 → 改号改圈 → 解除签到重排。
 *
 * <p>这一整块此前完全没有测试覆盖(ensureAuditionCircles / appendStageCompetitor /
 * appendParticipantWithRound / relocateCheckInCompetitor / removeCheckInCompetitor /
 * renumberMatchParticipants / pickCircleByNumberOrder),而它是现场最常用的操作。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class CheckInFlowTest {

    private static final String DB_PATH = "target/check-in-flow.db";

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
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;

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

    private Long insertPending(Long tid, Long stageId, String name, String number) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        return c.getId();
    }

    private List<TMatch> circleMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private long circleCountOf(Long stageId, Long competitorId) {
        List<Long> matchIds = circleMatches(stageId).stream().map(TMatch::getId).toList();
        return participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
    }

    /**
     * 模拟前端签到:按号码算出目标圈((n-1) % 圈数)后显式传给后端。
     * 后端已不再自行决定圈位,落圈必须由调用方指定。
     */
    private Long checkInByNumber(Long tid, Long stageId, String name, int number, List<TMatch> circles) {
        Long cid = insertPending(tid, stageId, name, String.valueOf(number));
        int idx = Math.floorMod(number - 1, circles.size());
        lifecycleService.appendStageCompetitor(stageId, cid, circles.get(idx).getId());
        return cid;
    }

    /** 场次内槽位与轮次序号应为 1..n 连续无空洞 */
    private void assertSlotsContiguous(Long matchId) {
        List<TMatchParticipant> parts = participantsOf(matchId);
        for (int i = 0; i < parts.size(); i++) {
            assertEquals((long) (i + 1), parts.get(i).getDisplaySlotIndex(),
                "场次[" + matchId + "]槽位应为 1..n 连续");
        }
    }

    /** 抽号页先建空圈,再逐个签到:每人恰好落一圈,圈内按号码升序,槽位连续。 */
    @Test
    void preBuiltCirclesThenCheckIn() {
        Long tid = newTournament("预建空圈签到");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);

        // 抽号页打开时按配置预建空圈
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        assertEquals(2, circles.size(), "应按配置预建 2 个空圈");
        assertEquals("ZONE-1", circles.get(0).getDisplayZone());
        assertEquals("ZONE-2", circles.get(1).getDisplayZone());
        assertEquals(0, participantsOf(circles.get(0).getId()).size(), "预建圈应为空");
        // 幂等:重复调用不新增圈
        lifecycleService.ensureAuditionCircles(stage.getId());
        assertEquals(2, circleMatches(stage.getId()).size(), "重复调用应幂等");

        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(checkInByNumber(tid, stage.getId(), "选手" + i, i, circles));
        }

        for (Long cid : ids) {
            assertEquals(1, circleCountOf(stage.getId(), cid), "每名选手应恰好挂入一个圈");
        }
        circles = circleMatches(stage.getId());
        assertEquals(2, participantsOf(circles.get(0).getId()).size(), "两圈应各 2 人");
        assertEquals(2, participantsOf(circles.get(1).getId()).size(), "两圈应各 2 人");

        // 按号码取模落圈:1、3 → 第 1 圈;2、4 → 第 2 圈
        assertEquals(List.of(ids.get(0), ids.get(2)),
            participantsOf(circles.get(0).getId()).stream()
                .map(TMatchParticipant::getCompetitorId).toList());
        assertEquals(List.of(ids.get(1), ids.get(3)),
            participantsOf(circles.get(1).getId()).stream()
                .map(TMatchParticipant::getCompetitorId).toList());
        // 圈内槽位 1..n 连续,且每个选手一个独立轮次
        for (TMatch c : circles) {
            assertSlotsContiguous(c.getId());
            assertEquals(2, matchRoundMapper.selectCount(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, c.getId())), "每名选手应有独立轮次");
        }
        // 签到后赛段被初始化(名单锁定)
        assertEquals(1L, stageMapper.selectById(stage.getId()).getIsInitialized());
    }

    /**
     * 圈场次尚未建立时签到:后端只按配置预建空圈,再按客户端给的目标圈落位。
     * 尚未拿到场次ID 时用 zoneIndex 指定(前端在圈栏还没落地时就是这么传的)。
     */
    @Test
    void checkInBeforeCirclesExistPreBuildsThenHonoursTargetZone() {
        Long tid = newTournament("未建圈先签到");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        assertEquals(0, circleMatches(stage.getId()).size(), "初始应无圈场次");

        Long first = insertPending(tid, stage.getId(), "选手1", "1");
        lifecycleService.appendStageCompetitor(stage.getId(), first, null, 2);

        List<TMatch> circles = circleMatches(stage.getId());
        assertEquals(2, circles.size(), "签到时应按配置预建 2 个圈");
        assertEquals(1, circleCountOf(stage.getId(), first), "选手应被挂入一个圈");
        assertEquals(first, participantsOf(circles.get(1).getId()).get(0).getCompetitorId(),
            "应按 zoneIndex=2 落入第 2 圈");
        assertEquals(0, participantsOf(circles.get(0).getId()).size(),
            "未指定的圈应保持为空(后端不再自行分配)");
    }

    /** 改号后改圈:号码 2 的选手改号为 5 后,应从第 2 圈移到第 1 圈。 */
    @Test
    void relocateMovesCompetitorByNewNumber() {
        Long tid = newTournament("改号改圈");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(checkInByNumber(tid, stage.getId(), "选手" + i, i, circles));
        }
        Long moved = ids.get(1);
        assertTrue(participantsOf(circles.get(1).getId()).stream()
                .anyMatch(p -> moved.equals(p.getCompetitorId())),
            "前置条件:2 号选手在第 2 圈");

        TCompetitor upd = new TCompetitor();
        upd.setId(moved);
        upd.setNumber("5");
        competitorMapper.updateById(upd);
        // 后端不再按号码推导圈位:由调用方按新号码算出目标圈(5 → 第 1 圈)并显式传入
        lifecycleService.relocateCheckInCompetitor(stage.getId(), moved, circles.get(0).getId());

        circles = circleMatches(stage.getId());
        assertEquals(1, circleCountOf(stage.getId(), moved), "改圈后仍应只挂一个圈");
        assertTrue(participantsOf(circles.get(0).getId()).stream()
                .anyMatch(p -> moved.equals(p.getCompetitorId())),
            "改号为 5 后应落到第 1 圈");
        assertTrue(participantsOf(circles.get(1).getId()).stream()
                .noneMatch(p -> moved.equals(p.getCompetitorId())),
            "不应再留在第 2 圈");
        for (TMatch c : circles) {
            assertSlotsContiguous(c.getId());
        }
    }

    /** 解除签到:移出圈场次并重排剩余选手的槽位与轮次,选手本人仍留在赛段。 */
    @Test
    void removeCheckInRenumbersRemainingSlots() {
        Long tid = newTournament("解除签到");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(checkInByNumber(tid, stage.getId(), "选手" + i, i, circles));
        }
        Long victim = ids.get(3);
        assertTrue(participantsOf(circles.get(1).getId()).stream()
                .anyMatch(p -> victim.equals(p.getCompetitorId())),
            "前置条件:4 号选手在第 2 圈");

        lifecycleService.removeCheckInCompetitor(stage.getId(), victim);

        assertEquals(0, circleCountOf(stage.getId(), victim), "解除后不应再挂在任何圈");
        circles = circleMatches(stage.getId());
        assertEquals(1, participantsOf(circles.get(1).getId()).size(), "第 2 圈应剩 1 人");
        assertSlotsContiguous(circles.get(1).getId());
        assertEquals(1, matchRoundMapper.selectCount(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, circles.get(1).getId())), "轮次应同步移除");
        // 参赛方本身仍在赛段里(只是不参与本赛段场次)
        assertEquals(OutcomeStatusEnum.PENDING.getCode(),
            competitorMapper.selectById(victim).getOutcomeStatus());
    }

    /** 已打分的选手不得改号/解除签到,避免现场成绩被静默丢弃。 */
    @Test
    void scoredCompetitorCannotBeMovedOrRemoved() {
        Long tid = newTournament("已打分不可改签到");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(checkInByNumber(tid, stage.getId(), "选手" + i, i, circles));
        }
        Long scored = ids.get(0);
        TMatchRound round = matchRoundMapper.selectOne(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, circles.get(0).getId())
            .eq(TMatchRound::getCompetitorId, scored)
            .last("limit 1"));
        assertTrue(round != null, "选手中应有独立轮次");
        TRoundScore score = new TRoundScore();
        score.setTournamentId(tid);
        score.setRoundId(round.getId());
        score.setCompetitorId(scored);
        score.setRefereeId(0L);
        score.setScore(BigDecimal.valueOf(88));
        score.setDimension(StageConstants.DIMENSION_MAIN);
        score.setAction(StageConstants.SCORE_ACTION_SCORE);
        roundScoreMapper.insert(score);

        ServiceException removeEx = assertThrows(ServiceException.class,
            () -> lifecycleService.removeCheckInCompetitor(stage.getId(), scored),
            "已打分的选手不允许解除签到");
        assertTrue(removeEx.getMessage().contains("已有打分记录"),
            "应说明原因是已打分,实际: " + removeEx.getMessage());

        ServiceException moveEx = assertThrows(ServiceException.class,
            () -> lifecycleService.relocateCheckInCompetitor(stage.getId(), scored, circles.get(0).getId()),
            "已打分的选手不允许改圈");
        assertTrue(moveEx.getMessage().contains("已有打分记录"),
            "应说明原因是已打分,实际: " + moveEx.getMessage());
        // 拦截后该选手仍留在原圈
        assertEquals(1, circleCountOf(stage.getId(), scored));
    }

    /** 显式指定落圈:合法目标圈生效,重复签到不重挂,非法目标圈报错。 */
    @Test
    void explicitTargetCircleIsValidated() {
        Long tid = newTournament("指定落圈");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());

        Long cid = insertPending(tid, stage.getId(), "选手9", "9");
        lifecycleService.appendStageCompetitor(stage.getId(), cid, circles.get(1).getId());
        assertTrue(participantsOf(circles.get(1).getId()).stream()
                .anyMatch(p -> cid.equals(p.getCompetitorId())),
            "应落入手动指定的第 2 圈");

        // 已在场次中的选手再次签到:不重复挂载
        lifecycleService.appendStageCompetitor(stage.getId(), cid, circles.get(0).getId());
        assertEquals(1, circleCountOf(stage.getId(), cid), "重复签到不应重复挂载");

        // 目标圈不存在 → 报错
        Long other = insertPending(tid, stage.getId(), "选手10", "10");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.appendStageCompetitor(stage.getId(), other, 999999L),
            "不存在的目标圈应被拒绝");
        assertTrue(ex.getMessage().contains("目标圈场次不存在"),
            "应说明目标圈不合法,实际: " + ex.getMessage());
    }

    /** 外部抽签定序:按提交顺序写 seedRank;赛段初始化后锁定不可再改。 */
    @Test
    void setSeedOrderLocksAfterInitialize() {
        Long tid = newTournament("外部抽签定序");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 2);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i)));
        }

        SeedOrderBo bo = new SeedOrderBo();
        bo.setStageId(stage.getId());
        bo.setCompetitorIds(List.of(ids.get(2), ids.get(0), ids.get(1)));
        assertEquals(3, lifecycleService.setSeedOrder(bo), "应写入 3 个种子位");
        assertEquals(Long.valueOf(1L), competitorMapper.selectById(ids.get(2)).getSeedRank());
        assertEquals(Long.valueOf(2L), competitorMapper.selectById(ids.get(0)).getSeedRank());
        assertEquals(Long.valueOf(3L), competitorMapper.selectById(ids.get(1)).getSeedRank());

        // 签到落圈(单圈也要显式指定):首次落圈会初始化赛段,种子随即锁定
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        assertEquals(1, circles.size(), "单圈应按配置预建 1 个圈");
        for (Long cid : ids) {
            lifecycleService.appendStageCompetitor(stage.getId(), cid, circles.get(0).getId());
        }
        assertEquals(1L, stageMapper.selectById(stage.getId()).getIsInitialized(), "落圈时应初始化");
        lifecycleService.startStage(stage.getId());
        assertThrows(ServiceException.class, () -> lifecycleService.setSeedOrder(bo),
            "已初始化的赛段不允许再调整种子顺序");
    }

    /**
     * 后端不再自行决定圈位:多圈海选下未指定目标圈直接报错,不会"猜"一个圈把人塞进去。
     * 只有单圈(无需选择)时才允许不传。
     */
    @Test
    void backendRefusesToChooseCircle() {
        Long tid = newTournament("后端不决定圈位");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 2);
        lifecycleService.ensureAuditionCircles(stage.getId());
        assertEquals(2, circleMatches(stage.getId()).size(), "应预建 2 个空圈");

        Long cid = insertPending(tid, stage.getId(), "选手1", "1");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.appendStageCompetitor(stage.getId(), cid),
            "多圈海选未指定目标圈应报错");
        assertTrue(ex.getMessage().contains("请指定落圈"),
            "应说明需要由调用方指定圈子,实际: " + ex.getMessage());
        assertEquals(0, circleCountOf(stage.getId(), cid), "被拒后不应挂入任何圈");

        // 单圈海选:同样是显式配置的一个圈,同样需要客户端指定落圈
        Long tid2 = newTournament("单圈无需指定");
        TStageVo single = newAuditionStage(tid2, "海选", 1, 2);
        lifecycleService.ensureAuditionCircles(single.getId());
        List<TMatch> singleCircles = circleMatches(single.getId());
        assertEquals(1, singleCircles.size(), "单圈应按配置预建 1 个圈");

        Long only = insertPending(tid2, single.getId(), "选手2", "2");
        ServiceException singleEx = assertThrows(ServiceException.class,
            () -> lifecycleService.appendStageCompetitor(single.getId(), only),
            "单圈也必须由调用方指定目标圈");
        assertTrue(singleEx.getMessage().contains("请指定落圈"),
            "应说明需要指定圈子,实际: " + singleEx.getMessage());
        lifecycleService.appendStageCompetitor(single.getId(), only, singleCircles.get(0).getId());
        assertEquals(1, circleCountOf(single.getId(), only), "指定目标圈后应挂入");
    }

    /**
     * 前端指定哪圈就落哪圈:后端不再按"剩余名额余量/人数最少"二次调整,
     * 即使指定的是名额较小、人数更多的圈也照办。
     */
    @Test
    void explicitCircleIsHonouredWithoutRebalancing() {
        Long tid = newTournament("按指定落圈不重排");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("海选");
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(3L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":3,\"maxScore\":100,"
            + "\"circleAdvanceCounts\":[2,1]}");
        TStageVo stage = stageService.insertByBo(bo);

        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circleMatches(stage.getId());
        assertEquals(2, circles.size(), "应预建 2 个空圈");

        // 三人都显式指定第 2 圈(名额只有 1、且人数很快超过第 1 圈)——后端应完全照办
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Long cid = insertPending(tid, stage.getId(), "选手" + i, String.valueOf(i));
            ids.add(cid);
            lifecycleService.appendStageCompetitor(stage.getId(), cid, circles.get(1).getId());
        }

        circles = circleMatches(stage.getId());
        assertEquals(0, participantsOf(circles.get(0).getId()).size(),
            "未指定的圈不应被后端自动填充");
        assertEquals(List.of(ids.get(0), ids.get(1), ids.get(2)),
            participantsOf(circles.get(1).getId()).stream()
                .map(TMatchParticipant::getCompetitorId).toList(),
            "应全部落在显式指定的第 2 圈,且圈内按号码升序");
        assertSlotsContiguous(circles.get(1).getId());
    }
}
