package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.IRefereeMatchService;
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
 * 场景:开始签到(海选圈已建、选手已落圈)之后、海选赛尚未开始时,更换「裁判对应的圈」。
 *
 * <p>验证这条现场高频操作链:圈配置保存(PUT /game/stage/{id}/config,
 * 内部 = {@code updateConfig} + {@code ensureAuditionCircles})后:
 * <ul>
 *   <li>圈级裁判绑定(t_match_referee)按新配置重绑,而不是停在旧圈上;</li>
 *   <li>已签到选手的落圈(t_match_participant)不被裁判重绑冲掉;</li>
 *   <li>随后「开始海选」能正常通过(每圈都有裁判)或被守卫拦下并给出清晰提示。</li>
 * </ul>
 * 换圈只应改动裁判与圈的绑定,不应影响签到数据、也不应让开赛守卫误判。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionRefereeReassignAfterCheckInTest {

    private static final String DB_PATH = "target/audition-referee-reassign.db";

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
    @Autowired private TRefereeStageMapper refereeStageMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private IRefereeMatchService refereeMatchService;

    /** 签到后把两个圈的裁判对调:新绑定生效、签到不动、开赛照常通过。 */
    @Test
    void swappingCircleRefereesAfterCheckInKeepsCheckInAndStartsFine() {
        Long tid = newTournament("签到后换圈裁判");
        Long refA = newReferee(tid, "裁判甲");
        Long refB = newReferee(tid, "裁判乙");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 4,
            List.of(List.of(refA), List.of(refB)));

        // 开始签到(配置保存/签到页打开即建圈并绑裁判)
        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circlesOf(stage.getId());
        assertEquals(2, circles.size(), "应建出 2 个圈");
        assertEquals(List.of(refA), circleRefereeIds(circles.get(0).getId()));
        assertEquals(List.of(refB), circleRefereeIds(circles.get(1).getId()));

        // 两名选手分别落进 1、2 圈
        Long p1 = checkIn(tid, stage, "选手1", "1", circles.get(0).getId());
        Long p2 = checkIn(tid, stage, "选手2", "2", circles.get(1).getId());
        assertEquals(circles.get(0).getId(), matchOf(p1));
        assertEquals(circles.get(1).getId(), matchOf(p2));

        // 海选还没开始(DRAFT)时,把裁判对调:甲→2圈, 乙→1圈
        saveCircleRefereesAndSync(stage, 2, 4, List.of(List.of(refB), List.of(refA)));

        List<TMatch> after = circlesOf(stage.getId());
        assertEquals(List.of(refB), circleRefereeIds(after.get(0).getId()),
            "第 1 圈的裁判应换成乙");
        assertEquals(List.of(refA), circleRefereeIds(after.get(1).getId()),
            "第 2 圈的裁判应换成甲");
        assertEquals(List.of(refA, refB), stageRefereeIds(stage.getId()),
            "赛段级裁判应是两圈裁判的并集");

        // 换裁判不应动签到落圈
        assertEquals(after.get(0).getId(), matchOf(p1), "选手1 应仍在第 1 圈");
        assertEquals(after.get(1).getId(), matchOf(p2), "选手2 应仍在第 2 圈");

        // 每圈都有裁判 → 正常开赛
        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        // 从裁判端看:甲只该看到 2 圈、乙只该看到 1 圈(圈级可见性跟着换圈走)
        assertEquals(List.of(after.get(1).getId()), visibleMatchIds(tid, refA, "裁判甲", stage.getId()),
            "裁判甲换圈后应只看到第 2 圈");
        assertEquals(List.of(after.get(0).getId()), visibleMatchIds(tid, refB, "裁判乙", stage.getId()),
            "裁判乙换圈后应只看到第 1 圈");
    }

    /** 新增一名裁判进某个圈:并集同步,开赛通过。 */
    @Test
    void addingRefereeToCircleAfterCheckInSyncsStageBinding() {
        Long tid = newTournament("签到后加裁判");
        Long refA = newReferee(tid, "裁判甲");
        Long refC = newReferee(tid, "裁判丙");
        TStageVo stage = newAuditionStage(tid, "海选", 1, 2, List.of(List.of(refA)));

        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circlesOf(stage.getId());
        checkIn(tid, stage, "选手1", "1", circles.get(0).getId());

        saveCircleRefereesAndSync(stage, 1, 2, List.of(List.of(refA, refC)));

        assertEquals(List.of(refA, refC), circleRefereeIds(circles.get(0).getId()),
            "第 1 圈应同时挂甲、丙两位裁判");
        assertEquals(List.of(refA, refC), stageRefereeIds(stage.getId()));

        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());
    }

    /** 换圈开赛后,裁判端看到的选手应是自己新圈里的那批人,而不是旧圈的。 */
    @Test
    void refereeSeesOwnCirclePlayersAfterReassignAndStart() {
        Long tid = newTournament("换圈后看人");
        Long refA = newReferee(tid, "裁判甲");
        Long refB = newReferee(tid, "裁判乙");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 4,
            List.of(List.of(refA), List.of(refB)));

        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circlesOf(stage.getId());
        // 1 圈放 1、3 号,2 圈放 2 号
        Long p1 = checkIn(tid, stage, "选手1", "1", circles.get(0).getId());
        Long p3 = checkIn(tid, stage, "选手3", "3", circles.get(0).getId());
        Long p2 = checkIn(tid, stage, "选手2", "2", circles.get(1).getId());

        // 换圈:甲→2圈(只该看到 2 号),乙→1圈(该看到 1、3 号)
        saveCircleRefereesAndSync(stage, 2, 4, List.of(List.of(refB), List.of(refA)));
        lifecycleService.startStage(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());

        RefereeMatchVo viewB = refereeMatchService.myMatch(tid, refB, "裁判乙", stage.getId(), null);
        assertEquals(circles.get(0).getId(), viewB.getMatch().getId(), "乙应落在第 1 圈");
        assertEquals(List.of(p1, p3),
            viewB.getParticipants().stream().map(RefereeMatchVo.RefereeParticipantInfo::getCompetitorId).sorted().toList(),
            "乙应看到第 1 圈的 1、3 号");
        assertEquals(List.of("1", "3"),
            viewB.getParticipants().stream().map(RefereeMatchVo.RefereeParticipantInfo::getNumber).sorted().toList());

        RefereeMatchVo viewA = refereeMatchService.myMatch(tid, refA, "裁判甲", stage.getId(), null);
        assertEquals(circles.get(1).getId(), viewA.getMatch().getId(), "甲应落在第 2 圈");
        assertEquals(List.of(p2),
            viewA.getParticipants().stream().map(RefereeMatchVo.RefereeParticipantInfo::getCompetitorId).sorted().toList(),
            "甲应只看到第 2 圈的 2 号");
        assertEquals(List.of("2"),
            viewA.getParticipants().stream().map(RefereeMatchVo.RefereeParticipantInfo::getNumber).sorted().toList());
    }

    /** 换圈后把某个圈的裁判清空:开赛被守卫拦下并点名该圈,而不是静默无法打分。 */
    @Test
    void clearingCircleRefereeAfterCheckInBlocksStartWithClearMessage() {
        Long tid = newTournament("换圈后空裁判");
        Long refA = newReferee(tid, "裁判甲");
        Long refB = newReferee(tid, "裁判乙");
        TStageVo stage = newAuditionStage(tid, "海选", 2, 4,
            List.of(List.of(refA), List.of(refB)));

        lifecycleService.ensureAuditionCircles(stage.getId());
        List<TMatch> circles = circlesOf(stage.getId());
        checkIn(tid, stage, "选手1", "1", circles.get(0).getId());
        checkIn(tid, stage, "选手2", "2", circles.get(1).getId());

        // 把 2 圈的裁判挪走(第 2 圈留空)
        saveCircleRefereesAndSync(stage, 2, 4, List.of(List.of(refA, refB), List.of()));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.startStage(stage.getId()));
        assertTrue(ex.getMessage().contains("还没有裁判"),
            "应提示某圈缺裁判,实际: " + ex.getMessage());
        assertEquals(StageConstants.STAGE_DRAFT, stageMapper.selectById(stage.getId()).getStatus(),
            "被守卫拦下时赛段不应被开始");
    }

    // ===== 工具 =====

    /** 复刻 RefereeStageController.editConfig:先 updateConfig(同步赛段级),再 ensureAuditionCircles(重绑圈级)。 */
    private void saveCircleRefereesAndSync(TStageVo stage, int circles, int advanceCount,
                                           List<List<Long>> circleReferees) {
        TStageConfigBo cfg = new TStageConfigBo();
        cfg.setId(stage.getId());
        cfg.setName(stage.getName());
        cfg.setStageMode(stage.getStageMode());
        cfg.setTeamCountStart(0L);
        cfg.setTeamCountEnd((long) advanceCount);
        cfg.setRuleConfig(ruleConfig(circles, advanceCount, circleReferees));
        stageService.updateConfig(cfg);
        lifecycleService.ensureAuditionCircles(stage.getId());
    }

    private String ruleConfig(int circles, int advanceCount, List<List<Long>> circleReferees) {
        StringBuilder quotas = new StringBuilder("[");
        for (int i = 0; i < circles; i++) {
            quotas.append(i > 0 ? "," : "").append(Math.max(1, advanceCount / circles));
        }
        quotas.append("]");
        StringBuilder refs = new StringBuilder("[");
        for (int i = 0; i < circleReferees.size(); i++) {
            if (i > 0) {
                refs.append(',');
            }
            refs.append('[');
            List<Long> ref = circleReferees.get(i);
            for (int j = 0; j < ref.size(); j++) {
                if (j > 0) {
                    refs.append(',');
                }
                refs.append(ref.get(j));
            }
            refs.append(']');
        }
        refs.append(']');
        return "{\"mode\":\"AUDITION\",\"circles\":" + circles
            + ",\"advanceCount\":" + advanceCount + ",\"maxScore\":100,"
            + "\"circleAdvanceCounts\":" + quotas
            + ",\"circleRefereeIds\":" + refs + "}";
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private Long newReferee(Long tournamentId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name, int circles, int advanceCount,
                                      List<List<Long>> circleReferees) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(ruleConfig(circles, advanceCount, circleReferees));
        return stageService.insertByBo(bo);
    }

    private Long checkIn(Long tid, TStageVo stage, String name, String number, Long targetMatchId) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), targetMatchId);
        return c.getId();
    }

    private Long matchOf(Long competitorId) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getCompetitorId, competitorId));
        return parts.isEmpty() ? null : parts.get(0).getMatchId();
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<Long> circleRefereeIds(Long matchId) {
        return matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, matchId))
            .stream().map(TMatchReferee::getRefereeId).filter(java.util.Objects::nonNull)
            .sorted().toList();
    }

    private List<Long> stageRefereeIds(Long stageId) {
        return refereeStageMapper.selectList(Wrappers.<TRefereeStage>lambdaQuery()
                .eq(TRefereeStage::getStageId, stageId))
            .stream().map(TRefereeStage::getRefereeId).filter(java.util.Objects::nonNull)
            .sorted().toList();
    }

    /** 裁判端「本赛段我可见/可判的场次」:即裁判实际对应的圈。 */
    private List<Long> visibleMatchIds(Long tid, Long refereeId, String refereeName, Long stageId) {
        RefereeMatchVo vo = refereeMatchService.myMatch(tid, refereeId, refereeName, stageId, null);
        return vo.getMatches().stream().map(RefereeMatchVo.RefereeMatchInfo::getId).sorted().toList();
    }
}
