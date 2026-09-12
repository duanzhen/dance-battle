package com.dance.street.game.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新机制端到端:两圈海选(每圈 32 人、不同裁判)
 * 各圈前 8 共 16 人直入 32 强;两圈第 9-24 名共 32 人进入复活淘汰赛取 16;
 * 32 强 = 16 直入 + 16 复活,再 32→16→8→4→2→1 决出冠军。
 *
 * <p>名单流转全部走"单名单行 + 来源组":出口组(圈名次规则) → applyRoster 装配。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class RevivalFormatE2ETest {

    private static final String DB_PATH = "target/revival-e2e.db";

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
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;

    @Test
    void twoCircleAuditionWithRevivalToChampion() {
        TTournament tournament = new TTournament();
        tournament.setName("两圈海选复活赛制E2E");
        tournamentMapper.insert(tournament);
        Long tid = tournament.getId();

        // 两圈各两名裁判(不同裁判组)
        TReferee j1 = insertReferee(tid, "裁判1");
        TReferee j2 = insertReferee(tid, "裁判2");
        TReferee j3 = insertReferee(tid, "裁判3");
        TReferee j4 = insertReferee(tid, "裁判4");

        // 执行链:海选 → 复活(32→16) → 32强 → 16强 → 8强 → 半决赛 → 决赛
        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 16L, null,
            "{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":16,"
                + "\"circleAdvanceCounts\":[8,8],\"maxScore\":100,"
                + "\"circleRefereeIds\":[[" + j1.getId() + "," + j2.getId() + "],["
                + j3.getId() + "," + j4.getId() + "]]}");
        TStageVo revival = createStage(tid, "复活赛", "KNOCKOUT", 32L, 16L, audition.getId(), knockoutRule(32L, 16L));
        TStageVo round32 = createStage(tid, "32强", "KNOCKOUT", 32L, 16L, revival.getId(), knockoutRule(32L, 16L));
        TStageVo round16 = createStage(tid, "16强", "KNOCKOUT", 16L, 8L, round32.getId(), knockoutRule(16L, 8L));
        TStageVo round8 = createStage(tid, "8强", "KNOCKOUT", 8L, 4L, round16.getId(), knockoutRule(8L, 4L));
        TStageVo semi = createStage(tid, "半决赛", "KNOCKOUT", 4L, 2L, round8.getId(), knockoutRule(4L, 2L));
        TStageVo finalStage = createStage(tid, "决赛", "KNOCKOUT", 2L, 1L, semi.getId(), knockoutRule(2L, 1L));

        // 名单配置(收敛模型):
        // 复活赛名单 = 海选每圈第 9-24 名(2 组) → 替换掉默认"海选晋级"
        Long revivalRosterId = rosterService.listByTarget(revival.getId()).get(0).getId();
        addCircleRankGroups(revival.getId(), audition.getId(), "ANY", 9, 24); // 海选出口只按圈内名次取人(结果不限)
        rosterService.removeGroup(revivalRosterId, 0); // 去掉默认 ADVANCE 组

        // 32 强名单 = 海选每圈前 8(跨级直入,2 组) + 默认复活晋级组
        Long round32RosterId = rosterService.listByTarget(round32.getId()).get(0).getId();
        addCircleRankGroups(round32.getId(), audition.getId(), "ADVANCE", 1, 8);

        // 海选签到 64 人(种子 1..64,按号顺序均分两圈,每圈 32)
        for (int i = 1; i <= 64; i++) {
            insertPending(tid, audition.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(audition.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(audition.getId()).getStatus());

        // 两圈各自打分(每圈两名裁判各打一轮,分数按上场顺序递减,确保圈内名次确定)
        List<TMatch> circleMatches = auditionCircleMatches(audition.getId());
        assertEquals(2, circleMatches.size());
        assertEquals("ZONE-1", circleMatches.get(0).getDisplayZone());
        assertEquals("ZONE-2", circleMatches.get(1).getDisplayZone());
        // 两圈裁判不同:每圈各绑 2 名裁判,集合不相交
        List<Long> zone1Refs = circleRefereeIds(circleMatches.get(0).getId());
        List<Long> zone2Refs = circleRefereeIds(circleMatches.get(1).getId());
        assertEquals(2, zone1Refs.size());
        assertEquals(2, zone2Refs.size());
        assertTrue(zone1Refs.stream().noneMatch(zone2Refs::contains));
        scoreAuditionCircle(circleMatches.get(0), List.of(j1.getId(), j2.getId()));
        scoreAuditionCircle(circleMatches.get(1), List.of(j3.getId(), j4.getId()));

        lifecycleService.completeStage(audition.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(audition.getId()).getStatus());
        assertEquals(16, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(48, countOutcome(audition.getId(), OutcomeStatusEnum.ELIMINATED.getCode()));

        // 出口候选校验:复活两组各 16 人;32 强两组直入各 8 人
        RosterCandidatesVo revivalCandidates = rosterService.candidates(revivalRosterId);
        assertEquals(2, revivalCandidates.getGroups().size());
        assertEquals(16, revivalCandidates.getGroups().get(0).getCompetitors().size());
        assertEquals(16, revivalCandidates.getGroups().get(1).getCompetitors().size());

        // 复活:装配 32 名落选者 → 单轮淘汰取 16 胜者
        assertEquals(32, rosterService.applyRoster(revival.getId(), null));
        assertEquals(32, competitorCount(revival.getId()));
        runSingleRoundKnockout(revival.getId());
        assertEquals(16, countOutcome(revival.getId(), OutcomeStatusEnum.ADVANCE.getCode()));

        // 32 强名单:16 复活晋级 + 16 海选直入(每圈 8)
        RosterCandidatesVo round32Candidates = rosterService.candidates(round32RosterId);
        long directCandidateCount = round32Candidates.getGroups().stream()
            .filter(g -> audition.getId().equals(g.getSourceStageId()))
            .mapToLong(g -> g.getCompetitors().size())
            .sum();
        assertEquals(3, round32Candidates.getGroups().size());
        assertEquals(16, directCandidateCount);
        assertEquals(32, rosterService.applyRoster(round32.getId(), null));
        assertEquals(32, competitorCount(round32.getId()));
        long directEntered = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round32.getId()))
            .stream().filter(c -> audition.getId().equals(c.getSourceStageId())).count();
        long reviveEntered = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round32.getId()))
            .stream().filter(c -> revival.getId().equals(c.getSourceStageId())).count();
        assertEquals(16, directEntered);
        assertEquals(16, reviveEntered);

        // 一路 KO 到冠军
        runSingleRoundKnockout(round32.getId());
        assertEquals(16, countOutcome(round32.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        applyAndRunKnockout(round16.getId());
        assertEquals(8, countOutcome(round16.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        applyAndRunKnockout(round8.getId());
        assertEquals(4, countOutcome(round8.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        applyAndRunKnockout(semi.getId());
        assertEquals(2, countOutcome(semi.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        applyAndRunKnockout(finalStage.getId());
        assertEquals(1, countOutcome(finalStage.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertTrue(StageConstants.STAGE_SETTLED.equals(stageMapper.selectById(finalStage.getId()).getStatus()));
    }

    /**
     * 只有卡在「晋级线」的并列才开二海;名次段边界上的并列不开赛,
     * 并列者按并列名次一起进入名次段出口(数量可能多带一个)。
     *
     * <p>赛制:单圈海选 26 人,前 8 晋级;下游"复活赛"按圈内第 9~24 名取人。
     * 第 24、25 名同分(名次段末端并列,不在晋级线上)→ 不开二海,赛段直接结算。</p>
     */
    @Test
    void rankBoundaryTieDoesNotSpawnTiebreak() {
        TTournament tournament = new TTournament();
        tournament.setName("名次段边界同分加赛");
        tournamentMapper.insert(tournament);
        Long tid = tournament.getId();
        TReferee judge = insertReferee(tid, "裁判A");

        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 8L, null,
            "{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":8,\"circleAdvanceCounts\":[8],\"maxScore\":100,"
                + "\"circleRefereeIds\":[[\"" + judge.getId() + "\"]]}");
        TStageVo revival = createStage(tid, "复活赛", "KNOCKOUT", 32L, 16L, audition.getId(), knockoutRule(32L, 16L));

        // 复活赛名单 = 海选第 9~24 名(单圈:不限圈 + 圈内名次段)
        Long revivalRosterId = rosterService.listByTarget(revival.getId()).get(0).getId();
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(audition.getId());
        TStageRosterGroupBo group = new TStageRosterGroupBo();
        group.setSourceStageId(audition.getId());
        group.setResultFilter(RosterConstants.FILTER_ANY);
        group.setRankByZone(true);
        group.setRankStart(9);
        group.setRankEnd(24);
        group.setFillMode(RosterConstants.FILL_AUTO);
        group.setQuota(0);
        bo.setGroups(List.of(group));
        rosterService.addGroups(revival.getId(), bo);
        rosterService.removeGroup(revivalRosterId, 0); // 去掉默认"海选晋级"组

        for (int i = 1; i <= 26; i++) {
            insertPending(tid, audition.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(audition.getId());
        List<TMatch> circles = auditionCircleMatches(audition.getId());
        assertEquals(1, circles.size());
        // 第 24、25 名同分:恰好横跨名次段 9~24 的末端边界
        scoreAuditionCircleWithTie(circles.get(0), judge.getId(), 24);

        // 首次结算:晋级线(第 8 名)无并列 → 直接结算,不因名次段并列开赛
        lifecycleService.completeStage(audition.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(audition.getId()).getStatus());
        List<TMatch> tiebreaks = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, audition.getId())
            .likeRight(TMatch::getRemark, "同分加赛"));
        assertEquals(0, tiebreaks.size());

        List<TMatchParticipant> ranked = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, circles.get(0).getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        // 非晋级线的同分按号码牌定先后:第 24、25 名各自唯一(号码小的在前)
        assertEquals(1, ranked.stream().filter(p -> Long.valueOf(24L).equals(p.getRankInMatch())).count());
        assertEquals(1, ranked.stream().filter(p -> Long.valueOf(25L).equals(p.getRankInMatch())).count());
        assertEquals(16, rosterService.candidates(revivalRosterId).getGroups().stream()
            .filter(g -> audition.getId().equals(g.getSourceStageId()))
            .mapToLong(g -> g.getCompetitors().size()).sum());
    }

    /** 逐裁判打分:第 position 名与第 position+1 名同分,其余按上场顺序递减 */
    private void scoreAuditionCircleWithTie(TMatch match, Long refereeId, int position) {
        ensureMatchGaming(match.getId());
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            int slot = i + 1;
            BigDecimal score = (slot == position || slot == position + 1)
                ? BigDecimal.valueOf(100 - (position - 1))
                : BigDecimal.valueOf(100 - i);
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(score);
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /** 加赛场次判罚:参赛方按上场顺序递减打分(决出先后) */
    private void scoreTiebreakMatch(TMatch tiebreak, Long refereeId) {
        ensureMatchGaming(tiebreak.getId());
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, tiebreak.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(tiebreak.getId());
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(BigDecimal.valueOf(90 - i));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /**
     * 多圈海选的"晋级线二海"端到端:一圈晋级线上并列 → 本赛段内开加赛;
     * 加赛又并列 → 再开一轮(三海);决出后各圈晋级名额精确、圈数不变。
     */
    @Test
    void multiCircleAuditionAdvanceTieSpawnsSecondAudition() {
        TTournament tournament = new TTournament();
        tournament.setName("多圈海选晋级线二海");
        tournamentMapper.insert(tournament);
        Long tid = tournament.getId();
        TReferee zone1Judge = insertReferee(tid, "圈1裁判");
        TReferee zone2Judge = insertReferee(tid, "圈2裁判");

        TStageVo audition = createStage(tid, "海选", "AUDITION", 0L, 16L, null,
            "{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":16,\"circleAdvanceCounts\":[8,8],"
                + "\"maxScore\":100,\"circleRefereeIds\":[[\"" + zone1Judge.getId() + "\"],[\""
                + zone2Judge.getId() + "\"]]}");
        TStageVo round32 = createStage(tid, "32强", "KNOCKOUT", 32L, 16L, audition.getId(), knockoutRule(32L, 16L));

        // 32 人按号码均分两圈(号码 1~16 → 圈1,17~32 → 圈2)
        for (int i = 1; i <= 32; i++) {
            insertPending(tid, audition.getId(), "选手" + i, String.valueOf(i), i);
        }
        lifecycleService.startStage(audition.getId());
        List<TMatch> circles = auditionCircleMatches(audition.getId());
        assertEquals(2, circles.size());
        assertEquals("ZONE-1", circles.get(0).getDisplayZone());
        assertEquals("ZONE-2", circles.get(1).getDisplayZone());

        // 圈1 第 8、9 名同分(晋级线并列) → 二海;圈2 名次干净 → 不产生加赛
        scoreAuditionCircleWithTie(circles.get(0), zone1Judge.getId(), 8);
        scoreMatchByOrder(circles.get(1), zone2Judge.getId(), i -> BigDecimal.valueOf(100 - i));

        lifecycleService.completeStage(audition.getId());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(audition.getId()).getStatus());
        List<TMatch> tiebreaks = tiebreakerMatches(audition.getId());
        assertEquals(1, tiebreaks.size());
        assertTrue(tiebreaks.get(0).getRemark().contains("晋级名额"));
        assertEquals("ZONE-1", tiebreaks.get(0).getDisplayZone());
        // 明确晋级者 7(圈1)+ 8(圈2);并列两人保持待定
        assertEquals(15, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(2, countOutcome(audition.getId(), OutcomeStatusEnum.PENDING.getCode()));

        // 二海又同分 → 裁判判完即自动结算并再开一轮(三海):
        // 验证"同一边界最多 4 轮"的计数没被误拦,也验证二海是自动结算的
        scoreMatchByOrder(tiebreaks.get(0), zone1Judge.getId(), i -> BigDecimal.valueOf(80));
        List<TMatch> chain = tiebreakerMatches(audition.getId());
        assertEquals(2, chain.size());
        assertEquals("ZONE-1", chain.get(1).getDisplayZone());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(chain.get(0).getId()).getStatus());
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(audition.getId()).getStatus());

        // 三海分出胜负 → 赛段可结算:圈1 恰好 8 人晋级,全场 16 人
        scoreMatchByOrder(chain.get(1), zone1Judge.getId(), i -> BigDecimal.valueOf(90 - i));
        lifecycleService.completeStage(audition.getId());
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(audition.getId()).getStatus());
        assertEquals(16, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(0, countOutcome(audition.getId(), OutcomeStatusEnum.PENDING.getCode()));
        // 加赛场次不计入圈:正式圈场次仍为 2
        assertEquals(2, circleOnlyMatches(audition.getId()).size());

        // 下游名单按晋级取人:16 人(每圈 8)
        Long round32RosterId = rosterService.listByTarget(round32.getId()).get(0).getId();
        assertEquals(16, rosterService.candidates(round32RosterId).getGroups().stream()
            .filter(g -> audition.getId().equals(g.getSourceStageId()))
            .mapToLong(g -> g.getCompetitors().size()).sum());
    }

    /** 按上场顺序逐裁判打分(scoreFn 决定每人分数) */
    private void scoreMatchByOrder(TMatch match, Long refereeId, java.util.function.IntFunction<BigDecimal> scoreFn) {
        ensureMatchGaming(match.getId());
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(scoreFn.apply(i));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /** 本赛段的同分加赛场次(按创建顺序) */
    private List<TMatch> tiebreakerMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .likeRight(TMatch::getRemark, "同分加赛")
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    /** 正式圈场次(排除同分加赛) */
    private List<TMatch> circleOnlyMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId))
            .stream()
            .filter(m -> !(m.getRemark() != null && m.getRemark().startsWith("同分加赛")))
            .toList();
    }

    // ---- 工具方法 ----

    private TStageVo createStage(Long tid, String name, String mode, Long start, Long end, Long prevId, String rule) {
        TStageBo bo = baseStage(tid, name, mode, start, end, prevId);
        bo.setRuleConfig(rule);
        TStageVo vo = stageService.insertByBo(bo);
        return vo;
    }

    private TStageBo baseStage(Long tid, String name, String mode, Long start, Long end, Long prevId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setPrevStageId(prevId);
        bo.setIsInitialized(0L);
        return bo;
    }

    private String knockoutRule(Long teams, Long advance) {
        return "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"singleRound\":true,\"teamsCount\":" + teams
            + ",\"advanceCount\":" + advance + ",\"format\":\"BO1\",\"pairingMode\":\"SEQUENTIAL\"},"
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}";
    }

    private TReferee insertReferee(Long tid, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        return r;
    }

    /** 向目标赛段名单追加"每圈名次区间"两组来源(圈1/圈2),组内 fill=AUTO */
    private void addCircleRankGroups(Long targetStageId, Long sourceStageId, String resultFilter, int rankStart, int rankEnd) {
        com.dance.street.game.domain.bo.TStageRosterGroupBo g1 = new com.dance.street.game.domain.bo.TStageRosterGroupBo();
        g1.setSourceStageId(sourceStageId);
        g1.setResultFilter(resultFilter);
        g1.setZone("ZONE-1");
        g1.setRankStart(rankStart);
        g1.setRankEnd(rankEnd);
        g1.setRankByZone(true);
        g1.setFillMode(RosterConstants.FILL_AUTO);
        g1.setQuota(0);
        com.dance.street.game.domain.bo.TStageRosterGroupBo g2 = new com.dance.street.game.domain.bo.TStageRosterGroupBo();
        g2.setSourceStageId(sourceStageId);
        g2.setResultFilter(resultFilter);
        g2.setZone("ZONE-2");
        g2.setRankStart(rankStart);
        g2.setRankEnd(rankEnd);
        g2.setRankByZone(true);
        g2.setFillMode(RosterConstants.FILL_AUTO);
        g2.setQuota(0);
        com.dance.street.game.domain.bo.TStageRosterBo bo = new com.dance.street.game.domain.bo.TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setResultFilter(resultFilter);
        bo.setFillMode(RosterConstants.FILL_AUTO);
        bo.setGroups(List.of(g1, g2));
        rosterService.addGroups(targetStageId, bo);
    }

    private void insertPending(Long tid, Long stageId, String name, String number, int seed) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank((long) seed);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
    }

    private List<TMatch> auditionCircleMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<Long> circleRefereeIds(Long matchId) {
        return matchRefereeMapper.selectList(Wrappers.<com.dance.street.game.domain.TMatchReferee>lambdaQuery()
                .eq(com.dance.street.game.domain.TMatchReferee::getMatchId, matchId))
            .stream()
            .map(com.dance.street.game.domain.TMatchReferee::getRefereeId)
            .toList();
    }

    /** 给一个圈的场次逐裁判打分:分数 100,99,…(上场顺序递减,圈内名次确定) */
    private void scoreAuditionCircle(TMatch match, List<Long> refereeIds) {
        ensureMatchGaming(match.getId());
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertEquals(32, parts.size());
        for (Long refereeId : refereeIds) {
            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(match.getId());
            bo.setRefereeId(refereeId);
            List<ScoreEntryBo> scores = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                ScoreEntryBo se = new ScoreEntryBo();
                se.setCompetitorId(parts.get(i).getCompetitorId());
                se.setScore(BigDecimal.valueOf(100 - i));
                scores.add(se);
            }
            bo.setScores(scores);
            matchResultService.submitResult(bo);
        }
    }

    private void ensureMatchGaming(Long matchId) {
        TMatch m = matchMapper.selectById(matchId);
        if (m != null && !StageConstants.MATCH_GAMING.equals(m.getStatus())) {
            matchResultService.startMatch(matchId);
        }
    }

    /** 单轮淘汰赛:启动全部场次 → 依序判定(每场前位胜) → 完成赛段 */
    private void runSingleRoundKnockout(Long stageId) {
        lifecycleService.startStage(stageId);
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        assertTrue(matches.size() > 0);
        for (TMatch m : matches) {
            finishSingleMatch(m.getId());
        }
        lifecycleService.completeStage(stageId);
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stageId).getStatus());
    }

    /** 上一赛段结算后:名单装配(CONFIRMED) → 单轮淘汰 → 完成 */
    private void applyAndRunKnockout(Long stageId) {
        rosterService.applyRoster(stageId, null);
        runSingleRoundKnockout(stageId);
    }

    private void finishSingleMatch(Long matchId) {
        matchResultService.startMatch(matchId);
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertTrue(parts.size() >= 2);
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(null);
        Map<Long, String> outcomes = new java.util.HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            outcomes.put(parts.get(i).getCompetitorId(), i == 0 ? "WIN" : "LOSS");
        }
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    private long competitorCount(Long stageId) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }
}
