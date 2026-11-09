package com.dance.street.game.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.impl.roster.RosterProjectionService;
import com.dance.street.game.service.impl.roster.RosterProjectionService.AssembledRow;
import com.dance.street.game.service.impl.roster.RosterProjectionService.Projection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表征测试:钉住 {@link RosterProjectionService#project} 与 {@link RosterProjectionService#assembleRows}
 * 的现有输出,重构「切片/出口落座」的调用关系时必须保持原样通过(测试本身不随重构改动)。
 *
 * <p>钉住的口径:单入边按来源 mode 压紧(海选/排名)或原样复制(淘汰,含空洞与超容量→待落座);
 * 名额截断;多入边整单进待落位;多边取到同一人先到先得(putIfAbsent)且记首条边 id;
 * 非自动边(STREAM/MANUAL/无来源)被跳过;默认排序 final_rank 升序再 id;入场标签 ADVANCE/REVIVE。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class RosterProjectionServiceCharacterizationTest {

    private static final String DB_PATH = "target/roster-assembler-characterization.db";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH
            + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void clean() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private RosterProjectionService projectionService;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;

    // ---------------- project:单入边 ----------------

    @Test
    void singleInboundFromAuditionCompactsAndKeepsEntryTags() {
        Long tid = newTournament("char-audition");
        TStage source = newStage(tid, "海选", "AUDITION", 0L);
        // 取 9..14 名:9、10 晋级,其余复活;故意乱序插入
        List<Long> ranks = new ArrayList<>(List.of(9L, 10L, 11L, 12L, 13L, 14L));
        Collections.shuffle(ranks, new java.util.Random(7));
        for (Long r : ranks) {
            comp(tid, source.getId(), r, r <= 10 ? OutcomeStatusEnum.ADVANCE : OutcomeStatusEnum.ELIMINATED);
        }
        comp(tid, source.getId(), 3L, OutcomeStatusEnum.ADVANCE);   // 名次区间外,不取
        TStageRosterGroupBo g = edge(1001L, source.getId(), 9, 14, 0);
        TStage target = newStage(tid, "目标", "KNOCKOUT", 8L);

        Projection p = projectionService.project(target, List.of(g));

        assertFalse(p.holding());
        assertEquals(8, p.slotCount(), "容量 8 > 最大座 6");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), new ArrayList<>(p.bySlot().keySet()), "压紧:9→1 … 14→6");
        for (Map.Entry<Long, AssembledRow> e : p.bySlot().entrySet()) {
            AssembledRow r = e.getValue();
            assertEquals(e.getKey() + 8, r.source.getFinalRank(), "不得改写来源 final_rank");
            assertEquals(1001L, r.sourceGroupId);
            assertEquals(r.source.getFinalRank() <= 10 ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE,
                r.entryTag);
        }
        assertTrue(p.holdingRows().isEmpty());
    }

    @Test
    void singleInboundFromRankCompactsAndQuotaTruncatesBeforeSeating() {
        Long tid = newTournament("char-rank");
        TStage source = newStage(tid, "排名", "RANK", 0L);
        for (long r = 5; r <= 12; r++) {
            comp(tid, source.getId(), r, OutcomeStatusEnum.ADVANCE);
        }
        TStageRosterGroupBo g = edge(2001L, source.getId(), 5, 12, 3);   // quota 3
        TStage target = newStage(tid, "目标", "KNOCKOUT", 4L);

        Projection p = projectionService.project(target, List.of(g));

        assertFalse(p.holding());
        assertEquals(4, p.slotCount());
        assertEquals(List.of(1L, 2L, 3L), new ArrayList<>(p.bySlot().keySet()));
        assertEquals(List.of(5L, 6L, 7L),
            p.bySlot().values().stream().map(r -> r.source.getFinalRank()).toList(), "名额只取前 3 名");
        assertTrue(p.holdingRows().isEmpty());
    }

    @Test
    void singleInboundFromKnockoutCopiesSeatsKeepsHolesAndOverflowGoesPending() {
        Long tid = newTournament("char-knockout");
        TStage source = newStage(tid, "淘汰", "KNOCKOUT", 0L);
        for (long r : new long[]{1, 2, 4, 6}) {
            comp(tid, source.getId(), r, OutcomeStatusEnum.ADVANCE);
        }
        TStageRosterGroupBo g = edge(3001L, source.getId(), null, null, 0);
        TStage target = newStage(tid, "目标", "KNOCKOUT", 4L);

        Projection p = projectionService.project(target, List.of(g));

        assertFalse(p.holding());
        assertEquals(4, p.slotCount());
        assertEquals(List.of(1L, 2L, 4L), new ArrayList<>(p.bySlot().keySet()), "原样复制,3 号位是空洞");
        assertEquals(1, p.holdingRows().size(), "名次 6 超出容量 4:不占座,进待落座");
        assertNull(p.holdingRows().get(0).seedRank);
        assertEquals(6L, p.holdingRows().get(0).source.getFinalRank());
    }

    @Test
    void singleInboundWithStreamEdgeProducesNoRows() {
        Long tid = newTournament("char-stream");
        TStage source = newStage(tid, "海选", "AUDITION", 0L);
        comp(tid, source.getId(), 1L, OutcomeStatusEnum.ADVANCE);
        TStageRosterGroupBo g = edge(4001L, source.getId(), null, null, 0);
        g.setFillMode(RosterConstants.FILL_STREAM);
        TStage target = newStage(tid, "目标", "KNOCKOUT", 4L);

        Projection p = projectionService.project(target, List.of(g));

        assertFalse(p.holding());
        assertTrue(p.bySlot().isEmpty());
        assertTrue(p.holdingRows().isEmpty());
        assertEquals(4, p.slotCount());
    }

    // ---------------- project:多入边 ----------------

    @Test
    void multiInboundGoesToHoldingWithoutAnySeatAssignment() {
        Long tid = newTournament("char-multi");
        TStage a = newStage(tid, "半决A", "KNOCKOUT", 0L);
        TStage b = newStage(tid, "半决B", "KNOCKOUT", 0L);
        comp(tid, a.getId(), 1L, OutcomeStatusEnum.ADVANCE);
        comp(tid, b.getId(), 1L, OutcomeStatusEnum.ADVANCE);
        comp(tid, b.getId(), 2L, OutcomeStatusEnum.ADVANCE);
        TStage target = newStage(tid, "决赛", "KNOCKOUT", 2L);

        Projection p = projectionService.project(target, List.of(
            edge(5001L, a.getId(), null, null, 0), edge(5002L, b.getId(), null, null, 0)));

        assertTrue(p.holding());
        assertEquals(3, p.slotCount(), "max(plan 2, 人数 3)");
        assertTrue(p.bySlot().isEmpty());
        assertEquals(3, p.holdingRows().size());
        assertTrue(p.holdingRows().stream().allMatch(r -> r.seedRank == null), "多入边不算座位");
        assertEquals(List.of(5001L, 5002L, 5002L),
            p.holdingRows().stream().map(r -> r.sourceGroupId).toList());
    }

    // ---------------- assembleRows(autoCandidateRows) ----------------

    @Test
    void assembleRowsDedupsAcrossEdgesFirstEdgeWinsAndKeepsOrder() {
        Long tid = newTournament("char-dedup");
        TStage source = newStage(tid, "海选", "AUDITION", 0L);
        Map<Long, Long> idByRank = new LinkedHashMap<>();
        for (long r : new long[]{6, 2, 5, 1, 4, 3}) {   // 乱序插入
            idByRank.put(r, comp(tid, source.getId(), r,
                r <= 2 ? OutcomeStatusEnum.ADVANCE : OutcomeStatusEnum.ELIMINATED).getId());
        }
        TStageRosterGroupBo g1 = edge(6001L, source.getId(), 1, 4, 0);
        TStageRosterGroupBo g2 = edge(6002L, source.getId(), 3, 6, 0);

        List<AssembledRow> rows = projectionService.assembleRows(List.of(g1, g2));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
            rows.stream().map(r -> r.source.getFinalRank()).toList(), "先 g1 的 1..4,再 g2 新增的 5、6");
        assertEquals(List.of(6001L, 6001L, 6001L, 6001L, 6002L, 6002L),
            rows.stream().map(r -> r.sourceGroupId).toList(), "重复的 3、4 记首条边");
        assertEquals(List.of(idByRank.get(1L), idByRank.get(2L), idByRank.get(3L), idByRank.get(4L),
            idByRank.get(5L), idByRank.get(6L)), rows.stream().map(r -> r.source.getId()).toList());
        assertEquals(List.of("ADVANCE", "ADVANCE", "REVIVE", "REVIVE", "REVIVE", "REVIVE"),
            rows.stream().map(r -> r.entryTag).toList().stream()
                .map(t -> RosterConstants.ENTRY_ADVANCE.equals(t) ? "ADVANCE" : "REVIVE").toList());
        assertTrue(rows.stream().allMatch(r -> r.seedRank == null), "取人阶段不分配座位");
    }

    @Test
    void assembleRowsAppliesPerEdgeQuotaAndSkipsNonAutoEdges() {
        Long tid = newTournament("char-quota");
        TStage source = newStage(tid, "排名", "RANK", 0L);
        for (long r = 1; r <= 6; r++) {
            comp(tid, source.getId(), r, OutcomeStatusEnum.ADVANCE);
        }
        TStageRosterGroupBo quota2 = edge(7001L, source.getId(), 1, 6, 2);
        TStageRosterGroupBo stream = edge(7002L, source.getId(), 1, 6, 0);
        stream.setFillMode(RosterConstants.FILL_STREAM);
        TStageRosterGroupBo manual = edge(7003L, source.getId(), 1, 6, 0);
        manual.setFillMode(RosterConstants.FILL_MANUAL);
        TStageRosterGroupBo noSource = edge(7004L, null, null, null, 0);
        TStageRosterGroupBo second = edge(7005L, source.getId(), 5, 6, 1);   // quota 1:只取 5

        List<AssembledRow> rows = projectionService.assembleRows(List.of(quota2, stream, manual, noSource, second));

        assertEquals(List.of(1L, 2L, 5L), rows.stream().map(r -> r.source.getFinalRank()).toList());
        assertEquals(List.of(7001L, 7001L, 7005L), rows.stream().map(r -> r.sourceGroupId).toList());
    }

    // ---------------- 出口切片/落座是投影的真实入口 ----------------

    @Test
    void assemblerExitApplyIsTheSamePathAsProjectSingleEdge() {
        Long tid = newTournament("char-exit-apply");
        TStage source = newStage(tid, "海选", "AUDITION", 0L);
        for (long r = 9; r <= 12; r++) {
            comp(tid, source.getId(), r, OutcomeStatusEnum.ADVANCE);
        }
        TStageRosterGroupBo g = edge(8001L, source.getId(), 9, 12, 0);
        TStage target = newStage(tid, "目标", "KNOCKOUT", 4L);

        List<AssembledRow> viaExitApply = projectionService.exitApply(g, () -> projectionService.groupRows(g), 4);
        Projection p = projectionService.project(target, List.of(g));

        assertTrue(projectionService.shouldCompact(g));
        assertEquals(List.of(1L, 2L, 3L, 4L), viaExitApply.stream().map(r -> r.seedRank).toList());
        assertEquals(viaExitApply.stream().map(r -> r.source.getId()).toList(),
            p.bySlot().values().stream().map(r -> r.source.getId()).toList());
        // 非自动边:空列表且不取人
        TStageRosterGroupBo stream = edge(8002L, source.getId(), null, null, 0);
        stream.setFillMode(RosterConstants.FILL_STREAM);
        assertTrue(projectionService.exitApply(stream, () -> { throw new AssertionError("不应取人"); }, 4).isEmpty());
        assertTrue(projectionService.sliceEdge(stream, () -> { throw new AssertionError("不应取人"); }).isEmpty());
    }

    // ---------------- helpers ----------------

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStage newStage(Long tid, String name, String mode, Long start) {
        TStage s = new TStage();
        s.setTournamentId(tid);
        s.setName(name);
        s.setStageMode(mode);
        s.setStatus(StageConstants.STAGE_DRAFT);
        s.setTeamCountStart(start);
        stageMapper.insert(s);
        return s;
    }

    private TCompetitor comp(Long tid, Long stageId, Long finalRank, OutcomeStatusEnum outcome) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName("R" + finalRank);
        c.setNumber(String.valueOf(finalRank));
        c.setFinalRank(finalRank);
        c.setOutcomeStatus(outcome.getCode());
        competitorMapper.insert(c);
        return c;
    }

    private static TStageRosterGroupBo edge(Long id, Long sourceStageId, Integer rankStart, Integer rankEnd, int quota) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setId(id);
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(RosterConstants.FILTER_ANY);
        g.setRankStart(rankStart);
        g.setRankEnd(rankEnd);
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(quota);
        return g;
    }
}
