package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多圈 + 每圈多裁判 + 多次加赛(二海/三海)之后的最终晋级名单必须精确。
 *
 * <p>场景:2 圈 × 6 人,每圈 4 个名额、每圈 2 名裁判;</p>
 * <ul>
 *   <li>1 圈:1/2/3 号 9 分直接晋级;4/5/6 号 7 分同分争最后 1 个 → 二海 3 选 1(4 号胜)</li>
 *   <li>2 圈:7/8 号 9 分直接晋级;9/10/11 号 7 分同分争最后 2 个 → 二海 3 选 2
 *       (9 号直接进,10/11 仍同分)→ 三海 2 选 1(10 号胜);12 号 3 分淘汰</li>
 * </ul>
 *
 * <p>断言:最终晋级 8 人且正好是 1/2/3/4/7/8/9/10;名次连续唯一;圈内名次无空缺;
 * 下一赛段按默认的「每圈 1~4 名」出口收到的人也正好是这 8 个。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionMultiCircleTiebreakAdvanceTest {

    private static final String DB_PATH = "target/audition-multi-circle-tiebreak.db";

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
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private SettlementSupport settlementSupport;

    @Test
    void multiCircleMultiRefereeChainedTiebreakersProduceExactAdvanceList() {
        Long tid = newTournament("多圈多裁判连环加赛");
        TStageVo stage = newAuditionStage(tid, "海选", 2, List.of(4, 4), 8);
        lifecycleService.ensureAuditionCircles(stage.getId());

        // 下游 8 强:不手配出口,吃"海选按圈自动生成的默认出口"(每圈 1~4 名)——
        // 这同时锁住"多圈默认出口"的口径
        TStageVo round8 = createStage(tid, "8强", 8L, 4L, stage.getId());
        List<TStageRosterGroupBo> defaultExits = rosterService.groupsOfStage(round8.getId());
        assertEquals(2, defaultExits.size(), "多圈海选应默认生成 2 条按圈出口");
        Map<String, TStageRosterGroupBo> exitByZone = defaultExits.stream()
            .collect(Collectors.toMap(TStageRosterGroupBo::getZone, g -> g));
        assertEquals(Set.of("ZONE-1", "ZONE-2"), exitByZone.keySet());
        for (TStageRosterGroupBo g : defaultExits) {
            assertEquals(1, g.getRankStart());
            assertEquals(4, g.getRankEnd());
            assertTrue(Boolean.TRUE.equals(g.getRankByZone()), "出口按圈内名次取人");
        }

        // 每圈 2 名裁判
        List<TMatch> circles = circlesOf(stage.getId());
        assertEquals(2, circles.size(), "应建出 2 个圈");
        TMatch circle1 = circles.get(0);
        TMatch circle2 = circles.get(1);
        Long refA = insertReferee(tid, "裁判A");
        Long refB = insertReferee(tid, "裁判B");
        Long refC = insertReferee(tid, "裁判C");
        Long refD = insertReferee(tid, "裁判D");
        bindRefereeToCircle(circle1.getId(), refA, tid);
        bindRefereeToCircle(circle1.getId(), refB, tid);
        bindRefereeToCircle(circle2.getId(), refC, tid);
        bindRefereeToCircle(circle2.getId(), refD, tid);

        // 1~6 号进 1 圈,7~12 号进 2 圈
        for (int no = 1; no <= 12; no++) {
            putPlayerInCircle(tid, stage, "选手" + no, String.valueOf(no),
                no <= 6 ? circle1.getId() : circle2.getId());
        }
        lifecycleService.startStage(stage.getId());

        // 一海:每圈两名裁判给同样的分数(聚合规则不影响排序)
        scoreAllReferees(circle1.getId(), Map.of(
            1, "9", 2, "9", 3, "9", 4, "7", 5, "7", 6, "7"));
        scoreAllReferees(circle2.getId(), Map.of(
            7, "9", 8, "9", 9, "7", 10, "7", 11, "7", 12, "3"));

        // 完成一海 → 两圈各生成一场二海
        assertTrue(lifecycleService.completeStage(stage.getId()).getTiebreaker(),
            "两圈都有同分,点完成赛段应提示需要加赛");
        assertEquals(2, tiebreakersOf(stage.getId()).size(), "两圈各应生成一场二海");

        TMatch second1 = latestTiebreakerOfZone(stage.getId(), "ZONE-1");
        TMatch second2 = latestTiebreakerOfZone(stage.getId(), "ZONE-2");
        assertEquals(List.of("4", "5", "6"), numbersOf(participantsOf(second1.getId())),
            "1 圈二海应是 4/5/6 号 3 选 1");
        assertEquals(List.of("9", "10", "11"), numbersOf(participantsOf(second2.getId())),
            "2 圈二海应是 9/10/11 号 3 选 2");
        assertEquals(2, refereeIdsOf(second1.getId()).size(), "二海沿用原圈的 2 名裁判");

        // 二海:1 圈 4 号直接胜出;2 圈 9 号直接进,10/11 再次同分 → 三海
        scoreAllReferees(second1.getId(), Map.of(4, "9", 5, "6", 6, "5"));
        scoreAllReferees(second2.getId(), Map.of(9, "9", 10, "7", 11, "7"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getTiebreaker(),
            "二海仍有同分,应再生成三海");
        assertEquals(3, tiebreakersOf(stage.getId()).size(), "加上三海共 3 场加赛");

        TMatch third2 = latestTiebreakerOfZone(stage.getId(), "ZONE-2");
        assertEquals(List.of("10", "11"), numbersOf(participantsOf(third2.getId())),
            "三海应是 10/11 号 2 选 1");

        // 三海:10 号胜出 → 全部决出,赛段可结束
        scoreAllReferees(third2.getId(), Map.of(10, "9", 11, "7"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(),
            "三海判完后点完成赛段应结束赛段");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());

        // ===== 最终晋级名单 =====
        List<TCompetitor> comps = competitorsOf(stage.getId());
        List<String> advancers = comps.stream()
            .filter(c -> OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .map(TCompetitor::getNumber)
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();
        assertEquals(List.of("1", "2", "3", "4", "7", "8", "9", "10"), advancers,
            "最终晋级应正好是每圈 4 人:1/2/3/4 与 7/8/9/10");

        // 淘汰的也要准确:5/6(1 圈二海落败)、11(三海落败)、12(一海垫底)
        List<String> eliminated = comps.stream()
            .filter(c -> OutcomeStatusEnum.ELIMINATED.getCode().equals(c.getOutcomeStatus()))
            .map(TCompetitor::getNumber)
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();
        assertEquals(List.of("5", "6", "11", "12"), eliminated);

        // 名次:1..8 连续不重复
        List<Long> ranks = comps.stream()
            .filter(c -> OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .map(TCompetitor::getFinalRank)
            .filter(Objects::nonNull)
            .sorted()
            .toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ranks, "晋级名次应连续 1..8");

        // 每个晋级者在自己的场次里都要有圈内名次,否则下游按"每圈 1~4 名"取人会漏人
        for (TCompetitor c : comps) {
            if (!OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            List<TMatchParticipant> rows = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stage.getId()))
                .stream()
                .flatMap(m -> partsOf(m.getId()).stream())
                .filter(p -> Objects.equals(p.getCompetitorId(), c.getId()))
                .toList();
            assertTrue(rows.stream().anyMatch(p -> p.getRankInMatch() != null),
                c.getNumber() + " 号在圈内应有名次(rank_in_match)");
        }

        // ===== 端到端:下游按"每圈 1~4 名"取人,收到的人也正好是这 8 个 =====
        placeAllHolding(round8.getId());
        assertEquals(8, rosterService.applyRoster(round8.getId(), null), "下游应带入 8 人");
        List<String> brought = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, round8.getId()))
            .stream().map(TCompetitor::getNumber)
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .toList();
        assertEquals(List.of("1", "2", "3", "4", "7", "8", "9", "10"), brought,
            "下游收到的名单应与海选晋级名单完全一致");
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name, int circles, List<Integer> quotas, int advanceCount) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":" + circles
            + ",\"advanceCount\":" + advanceCount
            + ",\"maxScore\":10,\"circleAdvanceCounts\":" + quotas + "}");
        return stageService.insertByBo(bo);
    }

    private TStageVo createStage(Long tid, String name, Long start, Long end, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + start
            + ",\"advanceCount\":" + end + ",\"format\":\"BO1\",\"pairingMode\":\"SEED\"}}");
        return stageService.insertByBo(bo);
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

    private void putPlayerInCircle(Long tid, TStageVo stage, String name, String number, Long matchId) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), matchId);
    }

    /** 本场绑定的每名裁判各提交一次,分数按"号码 → 分值"给出(与上场顺序无关) */
    private void scoreAllReferees(Long matchId, Map<Integer, String> scoreByNumber) {
        ensureMatchGaming(matchId);
        List<Long> refIds = refereeIdsOf(matchId);
        assertTrue(refIds.size() >= 2, "本用例要求每场至少 2 名裁判,实际 " + refIds.size());
        for (Long refId : refIds) {
            List<TMatchParticipant> parts = participantsOf(matchId);
            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(matchId);
            bo.setRefereeId(refId);
            List<ScoreEntryBo> scores = new ArrayList<>();
            for (TMatchParticipant p : parts) {
                TCompetitor c = competitorMapper.selectById(p.getCompetitorId());
                String value = scoreByNumber.get(Integer.parseInt(c.getNumber()));
                assertNotNull(value, "缺少 " + c.getNumber() + " 号的分数");
                ScoreEntryBo se = new ScoreEntryBo();
                se.setCompetitorId(p.getCompetitorId());
                se.setDimension("MAIN");
                se.setAction("SCORE");
                se.setScore(new BigDecimal(value));
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

    private List<Long> refereeIdsOf(Long matchId) {
        return matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, matchId))
            .stream().map(TMatchReferee::getRefereeId).toList();
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

    /** 某圈最新一场加赛(同一圈可能连环加赛:二海→三海) */
    private TMatch latestTiebreakerOfZone(Long stageId, String zone) {
        List<TMatch> list = tiebreakersOf(stageId).stream()
            .filter(m -> zone.equals(m.getDisplayZone()))
            .toList();
        assertTrue(!list.isEmpty(), "找不到 " + zone + " 的加赛场次");
        return list.get(list.size() - 1);
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private List<TMatchParticipant> partsOf(Long matchId) {
        return participantsOf(matchId);
    }

    private List<TCompetitor> competitorsOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
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

    /** 多圈海选 → 下游是多入口:人先进待落座,模拟导播拖到座位后再确认名单 */
    private void placeAllHolding(Long stageId) {
        List<TStageRosterEntry> rows = rosterService.entriesOf(stageId);
        Set<Long> used = new HashSet<>();
        rows.stream().map(TStageRosterEntry::getSlot).filter(Objects::nonNull).forEach(used::add);
        List<TStageRosterOrderBo.Item> items = new ArrayList<>();
        long next = 1L;
        for (TStageRosterEntry row : rows) {
            if (!StageConstants.SLOT_PLAYER.equals(row.getSlotKind()) || row.getSlot() != null) {
                continue;
            }
            while (used.contains(next)) {
                next++;
            }
            TStageRosterOrderBo.Item it = new TStageRosterOrderBo.Item();
            it.setOverrideId(row.getId());
            it.setSeedRank(next);
            items.add(it);
            used.add(next);
        }
        if (!items.isEmpty()) {
            rosterService.reorderRoster(stageId, items);
        }
    }
}
