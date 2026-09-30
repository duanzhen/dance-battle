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
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
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
import com.dance.street.game.service.ITStageRosterService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选连环加赛:16 强出现二海 6 选 3,二海里又出现三海 4 选 1。
 *
 * <p>现场事故:三海结束后只有三海那 1 名晋级者保留下来,二海直接晋级的 2 人反而
 * 掉了(既不在晋级名单,也不是淘汰,像是被整段覆盖)。</p>
 *
 * <p>这里锁死最终口径:晋级人数 = 一海直接晋级 + 二海直接晋级 + 三海决出的名额,
 * 且每个人的赛段级状态唯一、名次连续不重复。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionChainedTiebreakerTest {

    private static final String DB_PATH = "target/audition-chained-tiebreaker.db";

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
    private ITStageRosterService rosterService;
    @Autowired
    private SettlementSupport settlementSupport;

    @Test
    void chainedTiebreakersKeepEveryAdvancer() {
        Long tid = newTournament("连环加赛");
        TStageVo stage = newAuditionStage(tid, "海选", 16);
        lifecycleService.ensureAuditionCircles(stage.getId());
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 20; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        // 一海:1-13 号 9 分稳进;14-19 号 7 分同分争最后 3 个名额;20 号 3 分淘汰
        for (int no = 1; no <= 13; no++) {
            score(circleId, cidOf(stage.getId(), no), new BigDecimal("9"));
        }
        for (int no = 14; no <= 19; no++) {
            score(circleId, cidOf(stage.getId(), no), new BigDecimal("7"));
        }
        score(circleId, cidOf(stage.getId(), 20), new BigDecimal("3"));
        lifecycleService.completeStage(stage.getId());

        TMatch second = tiebreakersOf(stage.getId()).get(0);
        assertEquals(6, participantsOf(second.getId()).size(), "二海应是 6 人同分");
        assertEquals(List.of("14", "15", "16", "17", "18", "19"),
            numbersOf(participantsOf(second.getId())), "二海参赛者应是 14-19 号");

        // 二海:14、15 号 9 分稳进,16-19 号 7 分同分争最后 1 个名额 → 三海 4 选 1
        score(second.getId(), cidOf(stage.getId(), 14), new BigDecimal("9"));
        score(second.getId(), cidOf(stage.getId(), 15), new BigDecimal("9"));
        for (int no = 16; no <= 19; no++) {
            score(second.getId(), cidOf(stage.getId(), no), new BigDecimal("7"));
        }

        // 加赛不自动结算:裁判判完最后一人后仍要保持进行中,由导播台点「完成赛段」触发结算
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(second.getId()).getStatus(),
            "二海全员判完后不得自动结算");
        assertTrue(lifecycleService.completeStage(stage.getId()).getTiebreaker(),
            "点完成赛段:二海再次同分应生成三海并返回「需要加赛」");

        List<TMatch> tiebreakers = tiebreakersOf(stage.getId());
        assertEquals(2, tiebreakers.size(), "二海同分应再生成一场三海");
        TMatch third = tiebreakers.get(1);
        assertEquals(4, participantsOf(third.getId()).size(), "三海应是 4 人同分");
        assertEquals(List.of("16", "17", "18", "19"),
            numbersOf(participantsOf(third.getId())), "三海参赛者应是 16-19 号");

        // 三海:16 > 17 > 18 > 19,只有 16 号晋级
        score(third.getId(), cidOf(stage.getId(), 16), new BigDecimal("9"));
        score(third.getId(), cidOf(stage.getId(), 17), new BigDecimal("8"));
        score(third.getId(), cidOf(stage.getId(), 18), new BigDecimal("7"));
        score(third.getId(), cidOf(stage.getId(), 19), new BigDecimal("6"));

        // 三海同样不自动结算,点「完成赛段」才结算并结束赛段
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(third.getId()).getStatus(),
            "三海全员判完后不得自动结算");
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(),
            "三海判完后点完成赛段应结束赛段");

        // 三海结束后,二海直接晋级的 2 人 + 三海决出的 1 人必须都在
        List<TCompetitor> comps = competitorsOf(stage.getId());
        Map<String, String> statusByNumber = comps.stream()
            .collect(Collectors.toMap(TCompetitor::getNumber, TCompetitor::getOutcomeStatus, (a, b) -> a));
        List<String> advancers = comps.stream()
            .filter(c -> OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .map(TCompetitor::getNumber)
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();

        assertEquals("ADVANCE", statusByNumber.get("14"), "二海直接晋级的 14 号不应丢掉");
        assertEquals("ADVANCE", statusByNumber.get("15"), "二海直接晋级的 15 号不应丢掉");
        assertEquals("ADVANCE", statusByNumber.get("16"), "三海胜者 16 号应晋级");
        assertEquals(16, advancers.size(), "共应晋级 16 人,实际:" + advancers);

        // 名次唯一且连续(1..16)
        List<Long> ranks = comps.stream()
            .filter(c -> OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .map(TCompetitor::getFinalRank)
            .filter(Objects::nonNull)
            .sorted()
            .toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L), ranks,
            "晋级者名次应是连续的 1..16,不重不漏");

        // 一海里的同分小组按最终名次依次顺延:6 人(14-19)的名次都要落到一海场次行上,
        // 否则下一赛段按「圈内第 1~16 名」取人时,rank_in_match 为空的人会被整段漏掉
        Map<String, Long> circleRankByNumber = partsOf(circleId).stream()
            .filter(p -> p.getRankInMatch() != null)
            .collect(Collectors.toMap(
                p -> competitorMapper.selectById(p.getCompetitorId()).getNumber(),
                TMatchParticipant::getRankInMatch, (a, b) -> a));
        for (int no = 14; no <= 19; no++) {
            assertTrue(circleRankByNumber.containsKey(String.valueOf(no)),
                no + " 号在一海场次里的名次不应为空(现有一海名次:" + circleRankByNumber + ")");
        }

        // 三海结算后赛段应已结束
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus(),
            "加赛全部决出后赛段应可结束");

        // 下一赛段按海选配置的「圈内第 1~16 名」取人(与 AuditionStageConfig/TTournamentServiceImpl 同一套规则)
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, stage.getId(),
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":16,\"advanceCount\":8,"
                + "\"format\":\"BO1\",\"pairingMode\":\"SEED\"},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        addAuditionExitGroup(round16.getId(), stage.getId(), 16);
        assertEquals(16, rosterService.applyRoster(round16.getId(), null),
            "二海直接晋级的 2 人必须被带入下一赛段(否则就是现场看到的「二海晋级的人没掉了」)");
        List<String> brought = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round16.getId()))
            .stream().map(TCompetitor::getNumber).sorted(
                (a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b))).toList();
        assertTrue(brought.contains("14") && brought.contains("15") && brought.contains("16"),
            "下一赛段应带上 14/15/16 号(实际:" + brought + ")");
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

    private TStageVo createStage(Long tid, String name, String mode, Long start, Long end,
                                 Long afterStageId, String rule) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(rule);
        return stageService.insertByBo(bo);
    }

    /** 海选出口:圈内第 1~advance 名(界面/AuditionStageConfig 与模板实例化都写这一套) */
    private void addAuditionExitGroup(Long targetStageId, Long auditionStageId, int advance) {
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(auditionStageId);
        bo.setResultFilter("ANY");
        bo.setFillMode("AUTO");
        bo.setQuota(0);
        TStageRosterGroupBo group = new TStageRosterGroupBo();
        group.setSourceStageId(auditionStageId);
        group.setResultFilter("ANY");
        group.setZone("ZONE-1");
        group.setRankByZone(true);
        group.setRankStart(1);
        group.setRankEnd(advance);
        group.setFillMode("AUTO");
        group.setQuota(0);
        bo.setGroups(List.of(group));
        rosterService.addGroups(targetStageId, bo);
    }

    private List<TMatchParticipant> partsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId));
    }

    private List<TMatch> circlesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId))
            .stream()
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
    }

    private List<TMatch> tiebreakersOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER)
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private List<TCompetitor> competitorsOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
    }

    private Long cidOf(Long stageId, int number) {
        TCompetitor c = competitorMapper.selectOne(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getNumber, String.valueOf(number))
            .last("limit 1"));
        assertNotNull(c, "找不到号码 " + number + " 的选手");
        return c.getId();
    }

    private List<String> numbersOf(List<TMatchParticipant> parts) {
        return parts.stream()
            .map(p -> competitorMapper.selectById(p.getCompetitorId()))
            .filter(Objects::nonNull)
            .map(TCompetitor::getNumber)
            .filter(Objects::nonNull)
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();
    }
}
