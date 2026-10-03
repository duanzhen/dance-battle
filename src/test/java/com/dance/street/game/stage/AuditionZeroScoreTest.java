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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选 0 分的特殊口径:0 分 = 缺席/弃权,<b>一定不晋级、也不占名额名次</b>。
 *
 * <p>现场事故:16 强名额只填满 15 个(15 人有分、其余 0 分)时,0 分选手会稳稳占住第 16 名,
 * 下一赛段按「圈内第 1~16 名」取人就把 0 分的人当候选人捞进了 16 强——看起来就是"0 分也晋级"。
 * 正确行为:0 分一个都不进,第 16 个位置留空(下一赛段签表轮空)。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionZeroScoreTest {

    private static final String DB_PATH = "target/audition-zero-score.db";

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
    private ITStageRosterService rosterService;
    @Autowired
    private ITMatchResultService matchResultService;
    @Autowired
    private SettlementSupport settlementSupport;

    /** 15 人有分、5 人 0 分:只晋级 15 人,第 16 个名额留空 → 下一赛段 1 场轮空。 */
    @Test
    void zeroScoreNeverAdvancesAndLeavesTheQuotaVacant() {
        Long tid = newTournament("0分不晋级");
        TStageVo stage = newAuditionStage(tid, "海选", 16);
        lifecycleService.ensureAuditionCircles(stage.getId());
        // 开赛前配好下游出口(上游开赛后不允许再改它的出口)
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, stage.getId(),
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":16,\"advanceCount\":8,"
                + "\"format\":\"BO1\",\"pairingMode\":\"SEED\"},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        addAuditionExitGroup(round16.getId(), stage.getId(), 16);
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 20; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        for (int no = 1; no <= 15; no++) {
            score(circleId, cidOf(stage.getId(), no), new BigDecimal("9"));
        }
        for (int no = 16; no <= 20; no++) {
            score(circleId, cidOf(stage.getId(), no), BigDecimal.ZERO);
        }
        lifecycleService.completeStage(stage.getId());

        assertEquals(15, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "0 分一定不晋级:16 个名额只填 15 个,剩下的留空");
        assertEquals(5, countOutcome(stage.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));
        assertTrue(tiebreakersOf(stage.getId()).isEmpty(), "0 分之间的并列不产生加赛");

        // 0 分选手不能占用「圈内第 1~16 名」:名次必须排到名额之外
        Map<String, Long> circleRankByNumber = rankByNumber(partsOf(circleId));
        for (int no = 16; no <= 20; no++) {
            Long rank = circleRankByNumber.get(String.valueOf(no));
            assertNotNull(rank, no + " 号也要有本场名次(不能为 null)");
            assertTrue(rank > 16, no + " 号是 0 分,名次必须在本圈名额之外,实际:" + rank);
        }

        // 下一赛段按海选出口「圈内第 1~16 名(结果不限)」取人:0 分的一个都不能带进来
        assertEquals(15, rosterService.applyRoster(round16.getId(), null),
            "0 分不晋级:下一赛段只能带上 15 人");
        assertEquals(15, competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, round16.getId())), "0 分选手不应出现在下一赛段名单里");

        // 第 16 个位置留空 → 16 人签表 1 场轮空
        lifecycleService.startStage(round16.getId());
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, round16.getId()));
        assertEquals(8, matches.size(), "16 人签表应为 8 场");
        long byes = matches.stream()
            .filter(m -> partsOf(m.getId()).size() == 1)
            .count();
        assertEquals(1, byes, "空出来的第 16 个位置应体现为 1 场轮空");
    }

    /** 加赛里打 0 分同样不晋级、不占名额:二海 2 人有分 + 4 人 0 分 → 只进 2 人,名额留空。 */
    @Test
    void zeroScoreInTiebreakerCannotFillTheQuota() {
        Long tid = newTournament("加赛0分");
        TStageVo stage = newAuditionStage(tid, "海选", 16);
        lifecycleService.ensureAuditionCircles(stage.getId());
        // 开赛前配好下游出口(上游开赛后不允许再改它的出口)
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, stage.getId(),
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":16,\"advanceCount\":8,"
                + "\"format\":\"BO1\",\"pairingMode\":\"SEED\"},\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        addAuditionExitGroup(round16.getId(), stage.getId(), 16);
        Long refereeId = insertReferee(tid, "裁判A");
        Long circleId = circlesOf(stage.getId()).get(0).getId();
        bindRefereeToCircle(circleId, refereeId, tid);
        for (int i = 1; i <= 20; i++) {
            putPlayerInCircle(tid, stage, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(stage.getId());

        // 13 人稳进;14-19 号 7 分同分争最后 3 个名额;20 号 0 分
        for (int no = 1; no <= 13; no++) {
            score(circleId, cidOf(stage.getId(), no), new BigDecimal("9"));
        }
        for (int no = 14; no <= 19; no++) {
            score(circleId, cidOf(stage.getId(), no), new BigDecimal("7"));
        }
        score(circleId, cidOf(stage.getId(), 20), BigDecimal.ZERO);
        lifecycleService.completeStage(stage.getId());

        List<TMatch> tiebreakers = tiebreakersOf(stage.getId());
        assertEquals(1, tiebreakers.size(), "应创建一场二海");
        TMatch second = tiebreakers.get(0);
        List<String> tbNumbers = partsOf(second.getId()).stream()
            .map(p -> competitorMapper.selectById(p.getCompetitorId()).getNumber())
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();
        assertEquals(List.of("14", "15", "16", "17", "18", "19"), tbNumbers,
            "0 分选手不参与加赛");

        // 二海:14、15 号 9 分晋级;16-19 号全部打 0 分(缺席)→ 剩下 1 个名额留空
        score(second.getId(), cidOf(stage.getId(), 14), new BigDecimal("9"));
        score(second.getId(), cidOf(stage.getId(), 15), new BigDecimal("9"));
        for (int no = 16; no <= 19; no++) {
            score(second.getId(), cidOf(stage.getId(), no), BigDecimal.ZERO);
        }

        // 加赛不自动结算:必须由导播台点「完成赛段」才结算
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(second.getId()).getStatus(),
            "二海全员判完后不得自动结算");
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(),
            "二海判完后点完成赛段:13 + 2 人晋级,赛段直接结束");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());

        assertEquals(15, countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "加赛打 0 分不晋级:13 + 2,剩余 1 个名额留空");
        Map<String, Long> circleRankByNumber = rankByNumber(partsOf(circleId));
        for (int no = 16; no <= 20; no++) {
            Long rank = circleRankByNumber.get(String.valueOf(no));
            assertNotNull(rank, no + " 号要有本场名次");
            assertTrue(rank > 16, no + " 号没进名额段,名次必须在名额之外,实际:" + rank);
        }

        // 出口按圈内名次取人:0 分的 4 个(16-19)加一海的 0 分(20)一个都不能带进来
        assertEquals(15, rosterService.applyRoster(round16.getId(), null),
            "加赛打 0 分的人也一个都不能进下一赛段");
        List<String> brought = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round16.getId()))
            .stream().map(TCompetitor::getNumber).sorted(
                (a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b))).toList();
        assertEquals(15, brought.size());
        assertTrue(brought.stream().noneMatch(n -> Integer.parseInt(n) >= 16),
            "进下一赛段的应全是正分选手,实际:" + brought);
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

    private List<TMatchParticipant> partsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId));
    }

    private Map<String, Long> rankByNumber(List<TMatchParticipant> parts) {
        return parts.stream()
            .filter(p -> p.getRankInMatch() != null)
            .collect(Collectors.toMap(
                p -> competitorMapper.selectById(p.getCompetitorId()).getNumber(),
                TMatchParticipant::getRankInMatch, (a, b) -> a));
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    private Long cidOf(Long stageId, int number) {
        TCompetitor c = competitorMapper.selectOne(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getNumber, String.valueOf(number))
            .last("limit 1"));
        assertNotNull(c, "找不到号码 " + number + " 的选手");
        return c.getId();
    }
}
