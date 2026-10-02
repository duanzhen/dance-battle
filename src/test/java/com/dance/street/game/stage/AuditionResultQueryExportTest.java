package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 海选结果的查询口径与 Excel 导出。
 *
 * <p>这两条路径此前完全没有测试(裁判端看成绩、导播台导出成绩都在用),
 * 搬进 {@code AuditionResultService} 时补上。重点验证两条口径:</p>
 * <ul>
 *   <li>二海分数只用于同分先后,<b>不进入主表总分</b>(现场最容易算错的一处);</li>
 *   <li>多圈导出带「圈」列,加赛另起一张 sheet 且不重名。</li>
 * </ul>
 *
 * @author duane
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class AuditionResultQueryExportTest {

    private static final String DB_PATH = "target/audition-result-query-export.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH
            + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private com.dance.street.game.service.impl.stage.AuditionResultService auditionResultService;

    /** 二海分数只用于决出先后,不得计入主表总分;加赛明细要能被查询到。 */
    @Test
    void queryKeepsTiebreakScoresOutOfMainTotal() {
        Fixture f = buildAuditionWithTiebreak("查询口径");

        AuditionResultVo vo = auditionResultService.queryAuditionResult(f.stageId());

        // 主表:8 名参赛方都在,按名次升序(未定名次的排最后)
        assertEquals(8, vo.getCompetitors().size(), "主表应含全部参赛方");
        Map<String, AuditionResultVo.CompetitorItem> byNumber = vo.getCompetitors().stream()
            .collect(Collectors.toMap(AuditionResultVo.CompetitorItem::getNumber, c -> c, (a, b) -> a));
        // 圈1 第 2 名(选手2)的原始分是 80;二海他拿了 9 分,主表总分必须仍是 80
        AuditionResultVo.CompetitorItem p2 = byNumber.get("2");
        assertNotNull(p2);
        assertEquals(0, p2.getScore().compareTo(new BigDecimal("80")),
            "二海分数不应计入主表总分,实际:" + p2.getScore());
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(), p2.getOutcomeStatus(), "二海胜出者应晋级");
        assertEquals(1, p2.getRefereeScores().size(), "每名选手应带上其所在圈裁判的分");
        assertEquals("裁判A", p2.getRefereeScores().get(0).getRefereeName());

        // 加赛明细:1 项(二海)、圈1、两名同分选手
        assertEquals(1, vo.getTiebreakers().size(), "应有一项二海明细");
        AuditionResultVo.TiebreakerItem tb = vo.getTiebreakers().get(0);
        assertEquals(1, tb.getRound(), "第一层加赛即二海");
        assertEquals("ZONE-1", tb.getZone(), "并列发生在圈1");
        assertEquals(List.of("2", "3"),
            tb.getCompetitors().stream().map(AuditionResultVo.CompetitorItem::getNumber).toList(),
            "二海参赛者应是圈1第 2、3 名");
    }

    /** 导出:多圈带「圈」列,加赛另起一张 sheet,内容能正常打开。 */
    @Test
    void exportWritesWorkbookWithMainAndTiebreakSheets() throws Exception {
        Fixture f = buildAuditionWithTiebreak("导出");

        MockHttpServletResponse response = new MockHttpServletResponse();
        auditionResultService.exportAuditionResult(f.stageId(), response);

        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8",
            response.getContentType());
        assertTrue(response.getHeader("Content-Disposition") != null
                && response.getHeader("Content-Disposition").contains(".xlsx"),
            "应带 xlsx 附件名,实际:" + response.getHeader("Content-Disposition"));
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 0, "导出内容不应为空");
        assertEquals('P', bytes[0], "xlsx 应为 zip(PK 头)");
        assertEquals('K', bytes[1]);

        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> sheetNames = wb.getSheets().map(Sheet::getName).toList();
            assertTrue(sheetNames.contains("海选成绩"), "应有主表,实际:" + sheetNames);
            assertTrue(sheetNames.stream().anyMatch(n -> n.startsWith("二海")),
                "应有一张二海表,实际:" + sheetNames);

            Sheet main = wb.getSheets().filter(s -> "海选成绩".equals(s.getName())).findFirst().orElseThrow();
            List<Row> rows = main.read();
            List<String> header = rows.get(0).getCells(0, rows.get(0).getCellCount()).stream()
                .map(c -> c.asString()).toList();
            assertEquals(List.of("号码", "选手名", "圈", "裁判A", "裁判B", "总分", "排名"), header,
                "多圈导出表头应含圈列与全部裁判列");
            // 首行数据:号码与圈标签要有值
            Row first = rows.get(1);
            assertNotNull(first.getCellText(0));
            assertTrue(first.getCellText(2) != null && !first.getCellText(2).isBlank(),
                "多圈时圈列应有圈标签(裁判名或第N圈)");
        }
    }

    // ==================================================================
    // 造数据:两圈各 4 人、每圈取 2;圈1 的 2、3 名同分 → 二海
    // ==================================================================

    private record Fixture(Long tournamentId, Long stageId) {
    }

    private Fixture buildAuditionWithTiebreak(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        Long tid = t.getId();
        Long refA = newReferee(tid, "裁判A");
        Long refB = newReferee(tid, "裁判B");

        TStageVo stage = newAuditionStage(tid, "海选", new long[]{2, 2},
            "[[\"" + refA + "\"],[\"" + refB + "\"]]");
        List<TMatch> circles = circleMatches(stage.getId());
        assertEquals(2, circles.size());

        checkIn(tid, stage, circles.get(0).getId(), 4);
        checkIn(tid, stage, circles.get(1).getId(), 4);
        lifecycleService.startStage(stage.getId());

        // 圈1:90 / 80 / 80 / 60 → 晋级线(第 2 名)并列 → 二海;圈2 干净
        scoreAll(circles.get(0).getId(), refA, List.of("90", "80", "80", "60"));
        scoreAll(circles.get(1).getId(), refB, List.of("90", "85", "70", "60"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getTiebreaker(),
            "圈1 晋级线同分应产生二海");

        // 二海:2 号 9 分胜出
        TMatch tiebreak = tiebreakersOf(stage.getId()).get(0);
        scoreAll(tiebreak.getId(), refA, List.of("9", "7"));
        assertTrue(lifecycleService.completeStage(stage.getId()).getCompleted(), "二海判完应能结算");
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stage.getId()).getStatus());
        return new Fixture(tid, stage.getId());
    }

    private Long newReferee(Long tid, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name, long[] quotas, String circleRefereeIdsJson) {
        long total = 0L;
        StringBuilder quotaJson = new StringBuilder("[");
        for (int i = 0; i < quotas.length; i++) {
            quotaJson.append(i > 0 ? "," : "").append(quotas[i]);
            total += quotas[i];
        }
        quotaJson.append("]");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(total);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":" + quotas.length
            + ",\"advanceCount\":" + total + ",\"maxScore\":100,"
            + "\"circleAdvanceCounts\":" + quotaJson
            + ",\"circleRefereeIds\":" + circleRefereeIdsJson + "}");
        TStageVo stage = stageService.insertByBo(bo);
        lifecycleService.ensureAuditionCircles(stage.getId());
        return stage;
    }

    /** 造 n 名选手并落进指定圈(号码全局递增,便于按号码断言) */
    private void checkIn(Long tid, TStageVo stage, Long matchId, int n) {
        long base = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()));
        for (int i = 1; i <= n; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(stage.getId());
            c.setType(0L);
            c.setName("选手" + (base + i));
            c.setNumber(String.valueOf(base + i));
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
            lifecycleService.appendStageCompetitor(stage.getId(), c.getId(), matchId);
        }
    }

    private void scoreAll(Long matchId, Long refereeId, List<String> values) {
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertEquals(values.size(), parts.size(), "打分人数应与参赛人数一致");
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(new BigDecimal(values.get(i)));
            scores.add(se);
        }
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    private List<TMatch> circleMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_NORMAL)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatch> tiebreakersOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER)
            .orderByAsc(TMatch::getId));
    }
}
