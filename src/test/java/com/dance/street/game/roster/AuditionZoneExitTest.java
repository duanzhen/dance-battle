package com.dance.street.game.roster;

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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单圈海选 + 「第1圈名次」出口:选手必须能进下一个赛段。
 *
 * <p>回归的事故(历史):单圈场次的 {@code display_zone} 曾是 {@code CENTER}(多圈才是 ZONE-1..n),
 * 而出口规则里的圈过滤写的是 {@code ZONE-1},名单候选按字符串直接比对一个都取不到——
 * 现场表现就是「海选结束了,选手没进 32 强」,而且不报任何错。</p>
 *
 * <p>现在圈编号统一:{@code ZONE-k} 恒为"第 k 个圈",单圈即 {@code ZONE-1};老库里的
 * CENTER 行仍按"第 1 个圈"解析,保证未重排的历史赛段继续能取到人。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionZoneExitTest {

    private static final String DB_PATH = "target/audition-zone-exit.db";

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

    @Test
    void zone1ExitTakesCandidatesFromSingleCircle() {
        assertEquals(2, runSingleCircleExit());
    }

    /** 单圈海选走一遍「出口按第 1 圈名次取人」,返回实际带入下一赛段的人数 */
    private int runSingleCircleExit() {
        Long tid = newTournament("单圈海选出口");
        TStageVo audition = newAuditionStage(tid, "海选", 2);
        TStageVo next = newKnockoutStage(tid, "4强", 2, audition.getId());

        // 先把圈建出来:单圈即第 1 圈,与多圈同一套 ZONE-k 命名
        lifecycleService.ensureAuditionCircles(audition.getId());
        assertEquals("ZONE-1", circlesOf(audition.getId()).get(0).getDisplayZone(),
            "前置条件:单圈海选的场次分区应为 ZONE-1");
        // 出口规则写 ZONE-1 —— 线上就是这么配的
        addExitGroup(next.getId(), audition.getId(), "ZONE-1", 2);
        // 出口生效后移除自动生成的「上一赛段·晋级」默认组(与模板/出口 UI 的处理一致),
        // 让这条出口成为唯一来源——线上数据就是这样,default 组在时会兜底取人,掩盖问题
        removeGeneratedDefault(next.getId(), audition.getId());

        // 4 名选手开赛 + 全员打分 + 结算(按分数取前 2 名晋级)
        Long refereeId = insertReferee(tid, "裁判A");
        bindRefereeToCircle(circlesOf(audition.getId()).get(0).getId(), refereeId, tid);
        for (int i = 1; i <= 4; i++) {
            putPlayerInCircle(tid, audition, "选手" + i, String.valueOf(i));
        }
        lifecycleService.startStage(audition.getId());
        Long circleId = circlesOf(audition.getId()).get(0).getId();
        List<TMatchParticipant> parts = participantsOf(circleId);
        score(circleId, parts.get(0).getCompetitorId(), new BigDecimal("9"));
        score(circleId, parts.get(1).getCompetitorId(), new BigDecimal("8"));
        score(circleId, parts.get(2).getCompetitorId(), new BigDecimal("7"));
        score(circleId, parts.get(3).getCompetitorId(), new BigDecimal("6"));
        assertTrue(lifecycleService.completeStage(audition.getId()).getCompleted(),
            "海选应正常结束");

        // 装名单:选手必须真的进下一个赛段(此前这里恒为 0)
        int brought = rosterService.applyRoster(next.getId(), null);
        assertEquals(2, brought, "「第1圈名次 1~2」应取到 2 人,实际带入 " + brought);
        List<TCompetitor> rows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, next.getId()));
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(c -> c.getSourceCompetitorId() != null),
            "带入的选手应记录来源参赛方");
        return brought;
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

    private TStageVo newKnockoutStage(Long tid, String name, int advanceCount, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart((long) advanceCount * 2);
        bo.setTeamCountEnd((long) advanceCount);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        return stageService.insertByBo(bo);
    }

    /** 下游赛段加一条来源出口:源赛段·第k圈·名次 1~end */
    private void addExitGroup(Long targetStageId, Long sourceStageId, String zone, int rankEnd) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setZone(zone);
        g.setRankByZone(true);
        g.setRankStart(1);
        g.setRankEnd(rankEnd);
        g.setFillMode("AUTO");
        g.setQuota(0);
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setGroups(List.of(g));
        rosterService.addGroups(targetStageId, bo);
    }

    /** 移除自动生成的默认衔接组(源=直接前驱、无圈/无名次段),与 TTournamentServiceImpl 口径一致 */
    private void removeGeneratedDefault(Long targetStageId, Long sourceStageId) {
        List<TStageRosterGroupBo> groups = rosterService.listByTarget(targetStageId).get(0).getGroups();
        for (int i = 0; i < groups.size(); i++) {
            TStageRosterGroupBo g = groups.get(i);
            if (Objects.equals(g.getSourceStageId(), sourceStageId)
                && g.getZone() == null && g.getRankStart() == null && g.getRankEnd() == null) {
                rosterService.removeGroup(targetStageId, i);
                return;
            }
        }
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
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

}
