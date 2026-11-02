package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterMoveBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.impl.roster.RosterEntryStore;
import com.dance.street.game.service.impl.roster.RosterGroupStore;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边规则:自动边 / 手动边 / 流入边 的名单落座口径。
 *
 * <p>{@code char*}:表征测试,钉住"改规则之前"必须保持不变的行为(单自动边、多自动边、首段流入边、就绪判定、
 * 显式放置操作的语义),在改动前后都必须通过。<br>
 * {@code rule*}:新规则 —— 一条自动边 + 手动边时,自动边照常按规则排座;手动的人优先占座,
 * 自动的人被占座时进待落位区(不顺移);同一选手只留手动那条;待落位区有人不能确认名单。</p>
 *
 * <p>手动边本身不取人:它是边表里的一条"这个赛段允许导播手工加人"的实体,手动的人是导播添加的记录
 * (origin=MANUAL 的中间层行)。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class RosterManualEdgeTest {

    private static final String DB_PATH = "target/roster-manual-edge.db";

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

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TStageRosterEntryMapper entryMapper;
    @Autowired private RosterGroupStore groupStore;
    @Autowired private RosterEntryStore entryStore;
    @Autowired private ITStageRosterService rosterService;

    // =====================================================================
    // 表征测试:改规则前后都必须通过
    // =====================================================================

    @Test
    void charSingleAutoEdgeSeatsBySourceRank() {
        Fx f = fixture("char-single", false, false);

        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(), holding(f.target));
        assertEquals(8, slotRows(f.target), "座位 1..8 每个都有一行");
    }

    @Test
    void charSingleAutoEdgeOverridesParkOccupantAndInsertShiftsEverythingByOne() {
        Fx f = fixture("char-ops", false, false);

        // 手工加人占 2 号位:原占位者顶到待落座区,其他人不动
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        assertEquals(List.of(f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.a(2)), holding(f.target));

        // INSERT:插到 1 号位,1 号及后面的人整体 +1(显式放置操作语义不变)
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(2), 1L, RosterConstants.PLACEMENT_INSERT);
        assertEquals(List.of(f.m(2), f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 5));
    }

    @Test
    void charSingleAutoEdgeWithoutManualEdgeReconcileLetsRuleWinSeatConflict() {
        Fx f = fixture("char-reconcile", false, false);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);

        entryStore.reconcileEntries(f.target.getId());

        // 没有手动边:对账时自动排座说了算,手动的人让座到待落位区(既有行为)
        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.m(1)), holding(f.target));
    }

    @Test
    void charSingleAutoEdgeWithoutManualEdgeSameCompetitorKeepsRuleRow() {
        Fx f = fixture("char-same", false, false);
        // 手动行里的人和规则选中的人是同一个(a3):对账后只留一行,且是规则行
        insertManualRow(f.target, f.a(3), 6L);

        entryStore.reconcileEntries(f.target.getId());

        assertEquals(1, rowsOf(f.target, f.a(3)).size());
        TStageRosterEntry only = rowsOf(f.target, f.a(3)).get(0);
        assertEquals(3L, only.getSlot());
        assertEquals(RosterConstants.ENTRY_ORIGIN_RULE, only.getOrigin());
    }

    @Test
    void charMultiAutoEdgesAllGoToHoldingAndConfirmIsBlocked() {
        Fx f = fixture("char-multi", true, false);

        assertEquals(List.of(), seats(f.target, 1, 8).stream().filter(Objects::nonNull).toList());
        assertEquals(sorted(f.a(1), f.a(2), f.a(3), f.a(4), f.m(1), f.m(2), f.m(3)), sortedList(holding(f.target)));
        assertThrows(ServiceException.class, () -> rosterService.applyRoster(f.target.getId(), null));
    }

    @Test
    void charStreamOnlyFirstStageHasNoPeopleAndIsReady() {
        Long tid = newTournament("char-stream");
        TStage target = newStage(tid, "首段", "KNOCKOUT", StageConstants.STAGE_DRAFT, 8L);
        TStageRosterGroupBo stream = edge(null, 0);
        stream.setFillMode(RosterConstants.FILL_STREAM);
        groupStore.saveGroups(target, List.of(stream));
        entryStore.rebuildEntries(target.getId());

        assertTrue(rosterService.isRosterReady(target.getId()), "流入边没有来源,不等任何赛段");
        assertTrue(entryStore.selectEntries(target.getId()).stream()
            .noneMatch(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind())));
    }

    @Test
    void charReadinessNeedsEverySourceSettledForAutoAndManualEdges() {
        Fx f = fixture("char-ready", false, true);
        assertTrue(rosterService.isRosterReady(f.target.getId()));

        // 手动边带来源:它的来源没结算,名单也不算就绪
        f.manualSource.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(f.manualSource);
        assertFalse(rosterService.isRosterReady(f.target.getId()));

        f.manualSource.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(f.manualSource);
        f.autoSource.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(f.autoSource);
        assertFalse(rosterService.isRosterReady(f.target.getId()));
    }

    // =====================================================================
    // 新规则:一条自动边 + 手动边
    // =====================================================================

    @Test
    void ruleAutoPlusManualEdgeSeatsAutoPeopleByRuleNotHolding() {
        Fx f = fixture("rule-basic", false, true);

        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(), holding(f.target), "手动边不再把自动边的人整单打进待落位区");
    }

    @Test
    void ruleManualPersonWithSeatAndNoConflictSitsThereAutoPeopleStay() {
        Fx f = fixture("rule-noconflict", false, true);

        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 6L, null);
        assertSeatsAfterNoConflict(f);

        entryStore.reconcileEntries(f.target.getId());
        assertSeatsAfterNoConflict(f);

        entryStore.syncPreAdvance(f.autoSource.getId());
        assertSeatsAfterNoConflict(f);
    }

    private void assertSeatsAfterNoConflict(Fx f) {
        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(f.m(1), seatOf(f.target, 6));
        assertEquals(List.of(), holding(f.target));
    }

    @Test
    void ruleManualPersonTakingAutoSeatSendsThatAutoPersonToHoldingAndNobodyShifts() {
        Fx f = fixture("rule-conflict", false, true);

        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        assertConflictResult(f);

        // 上游对账 / 实时同步 / 整表对账都不能把手动的人挤下去
        entryStore.reconcileEntries(f.target.getId());
        assertConflictResult(f);
        entryStore.syncPreAdvance(f.autoSource.getId());
        assertConflictResult(f);
        entryStore.rebuildEntriesOfDownstream(f.autoSource.getId());
        assertConflictResult(f);
    }

    private void assertConflictResult(Fx f) {
        assertEquals(List.of(f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.a(2)), holding(f.target), "被占座的自动人进待落位区,不顺移");
    }

    @Test
    void ruleManualRowAtAutoSeatWinsOverReconcileEvenWhenInsertedWithoutOverrideOp() {
        Fx f = fixture("rule-conflict-row", false, true);
        // 手动行直接占着 3 号位(模拟之前加的人),此时来源做一次对账
        insertManualRow(f.target, f.m(2), 3L);

        entryStore.reconcileEntries(f.target.getId());

        assertEquals(List.of(f.a(1), f.a(2), f.m(2), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.a(3)), holding(f.target));
    }

    @Test
    void ruleManualPersonWithoutSeatGoesToHoldingAndAutoSeatsUntouched() {
        Fx f = fixture("rule-holding", false, true);

        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.m(1)), holding(f.target));
        entryStore.reconcileEntries(f.target.getId());
        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.m(1)), holding(f.target));
    }

    @Test
    void ruleSameCompetitorPickedByManualAndAutoKeepsOnlyTheManualRow() {
        Fx f = fixture("rule-same", false, true);
        // 手动行里的人 a3 同时被自动边选中:只留手动那一条(座位 6),自动边给他的 3 号位空着
        insertManualRow(f.target, f.a(3), 6L);

        entryStore.reconcileEntries(f.target.getId());

        List<TStageRosterEntry> rows = rowsOf(f.target, f.a(3));
        assertEquals(1, rows.size(), "同一选手只留一条");
        assertEquals(RosterConstants.ENTRY_ORIGIN_MANUAL, rows.get(0).getOrigin());
        assertEquals(6L, rows.get(0).getSlot());
        TStageRosterEntry seat3 = entryAt(f.target, 3);
        assertFalse(StageConstants.SLOT_PLAYER.equals(seat3.getSlotKind()), "自动边给他的座位让空");
        assertEquals(List.of(), holding(f.target));
    }

    @Test
    void ruleExplicitPlacementOpsKeepTheirSemanticsWithManualEdge() {
        Fx f = fixture("rule-ops", false, true);

        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(2), 1L, RosterConstants.PLACEMENT_INSERT);

        assertEquals(List.of(f.m(2), f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 5),
            "INSERT 仍是 1 号及后面整体 +1,与没有手动边时一致");
    }

    @Test
    void ruleHoldingPersonBlocksConfirmWithClearMessageUntilPlaced() {
        Fx f = fixture("rule-confirm", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> rosterService.applyRoster(f.target.getId(), null));
        assertTrue(ex.getMessage().contains("待落位区还有 1 人未处理，无法确认名单"), ex.getMessage());

        TStageRosterMoveBo move = new TStageRosterMoveBo();
        move.setSourceCompetitorId(f.m(1));
        move.setTargetSeed(6L);
        rosterService.moveRosterRow(f.target.getId(), move);
        assertEquals(5, rosterService.applyRoster(f.target.getId(), null), "落位后可确认");
    }

    @Test
    void ruleSeveralAutoEdgesPlusManualEdgeStillAllGoToHolding() {
        Fx f = fixture("rule-multi-auto", true, true);

        assertTrue(seats(f.target, 1, 8).stream().allMatch(Objects::isNull));
        assertEquals(7, holding(f.target).size());
    }

    // =====================================================================
    // 拖动:待落位区的人拖到有人的座位 —— 插入 / 替换(与"加人"同一套语义)
    // =====================================================================

    @Test
    void dragHoldingOntoOccupiedSeatReplaceSendsOccupantToHoldingAndBlocksConfirm() {
        Fx f = fixture("drag-replace", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        move(f, f.m(1), 2L, null);   // 不传 placement = 替换(既有行为不变)

        assertEquals(List.of(f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.a(2)), holding(f.target), "被替换的人进待落位区");
        assertThrows(ServiceException.class, () -> rosterService.applyRoster(f.target.getId(), null),
            "被替换的人还在待落位区,不能确认名单");
    }

    @Test
    void dragHoldingOntoOccupiedSeatInsertShiftsFollowersAndKeepsHoldingEmpty() {
        Fx f = fixture("drag-insert", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        move(f, f.m(1), 2L, RosterConstants.PLACEMENT_INSERT);

        assertEquals(List.of(f.a(1), f.m(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 5));
        assertEquals(List.of(), holding(f.target));
        assertEquals(1, rowsOf(f.target, f.m(1)).size());
    }

    @Test
    void dragHoldingInsertPushesTailBeyondPlanIntoHolding() {
        Fx f = fixture("drag-insert-full", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(2), 8L, null);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        move(f, f.m(1), 1L, RosterConstants.PLACEMENT_INSERT);

        assertEquals(List.of(f.m(1), f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 5));
        assertEquals(List.of(f.m(2)), holding(f.target), "挤出计划规模的最后一位进待落位区,不删除");
    }

    @Test
    void dragHoldingOntoEmptySeatIgnoresPlacementAndMovesDirectly() {
        Fx f = fixture("drag-empty", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);

        move(f, f.m(1), 6L, RosterConstants.PLACEMENT_INSERT);

        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(f.m(1), seatOf(f.target, 6));
        assertEquals(List.of(), holding(f.target));
    }

    private void move(Fx f, Long sourceCompetitorId, Long seat, String placement) {
        TStageRosterMoveBo bo = new TStageRosterMoveBo();
        bo.setSourceCompetitorId(sourceCompetitorId);
        if (seat == null) {
            bo.setToHolding(true);
        } else {
            bo.setTargetSeed(seat);
            bo.setPlacement(placement);
        }
        rosterService.moveRosterRow(f.target.getId(), bo);
    }

    // =====================================================================
    // 拖动 INSERT 门禁:与加人插入同一来源已结算校验
    // =====================================================================

    @Test
    void dragHoldingInsertRejectedWhenSourceUnsettled() {
        Fx f = fixture("drag-insert-unsettled", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);
        f.autoSource.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(f.autoSource);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> move(f, f.m(1), 2L, RosterConstants.PLACEMENT_INSERT));
        assertTrue(ex.getMessage().contains("上游还有赛段没结束"), ex.getMessage());
        assertEquals(List.of(f.a(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.m(1)), holding(f.target), "被拒绝后名单不变");
    }

    @Test
    void dragHoldingReplaceAllowedWhenSourceUnsettled() {
        Fx f = fixture("drag-replace-unsettled", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);
        f.autoSource.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(f.autoSource);

        move(f, f.m(1), 2L, "REPLACE");

        assertEquals(List.of(f.a(1), f.m(1), f.a(3), f.a(4)), seats(f.target, 1, 4));
        assertEquals(List.of(f.a(2)), holding(f.target));
    }

    @Test
    void dragHoldingInsertSucceedsWhenSourcesSettled() {
        Fx f = fixture("drag-insert-settled-gate", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), null, RosterConstants.PLACEMENT_HOLDING);
        assertTrue(rosterService.isRosterReady(f.target.getId()));

        move(f, f.m(1), 2L, RosterConstants.PLACEMENT_INSERT);

        assertEquals(List.of(f.a(1), f.m(1), f.a(2), f.a(3), f.a(4)), seats(f.target, 1, 5));
        assertEquals(List.of(), holding(f.target));
    }

    // =====================================================================
    // 手动人移走后:座位留空,被挤的自动人不回填
    // =====================================================================

    @Test
    void manualLeaveSeatToHoldingKeepsSeatEmptyAndDisplacedStaysInHolding() {
        Fx f = fixture("leave-holding", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        assertEquals(List.of(f.a(2)), holding(f.target));

        move(f, f.m(1), null, null);   // 手动人拖回待落位区

        assertNull(seatOf(f.target, 2), "手动人移走后该座位保持空位");
        assertEquals(StageConstants.SLOT_BYE, entryAt(f.target, 2).getSlotKind());
        assertTrue(holding(f.target).contains(f.a(2)), "被挤的自动人仍在待落位区");
        assertTrue(holding(f.target).contains(f.m(1)));

        entryStore.reconcileEntries(f.target.getId());
        assertNull(seatOf(f.target, 2), "对账后仍不自动回填");
        assertEquals(StageConstants.SLOT_BYE, entryAt(f.target, 2).getSlotKind());
        assertTrue(holding(f.target).contains(f.a(2)));
        List<Long> expected = new ArrayList<>();
        expected.add(f.a(1)); expected.add(null); expected.add(f.a(3)); expected.add(f.a(4));
        assertEquals(expected, seats(f.target, 1, 4));
    }

    @Test
    void manualLeaveSeatToEmptySeatKeepsOldSeatEmptyAndDisplacedStaysInHolding() {
        Fx f = fixture("leave-empty", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        assertEquals(List.of(f.a(2)), holding(f.target));

        move(f, f.m(1), 6L, null);   // 拖到空座位

        assertNull(seatOf(f.target, 2), "原座位留空");
        assertEquals(f.m(1), seatOf(f.target, 6));
        assertEquals(List.of(f.a(2)), holding(f.target));

        entryStore.reconcileEntries(f.target.getId());
        assertNull(seatOf(f.target, 2), "对账后原座位仍空");
        assertEquals(f.m(1), seatOf(f.target, 6));
        assertTrue(holding(f.target).contains(f.a(2)));
    }

    @Test
    void deleteManualOccupantLeavesSeatEmptyAndDisplacedStaysInHolding() {
        Fx f = fixture("leave-delete", false, true);
        override(f.target, RosterConstants.OVERRIDE_ADD_SOURCE, f.m(1), 2L, null);
        Long manualRowId = rowsOf(f.target, f.m(1)).get(0).getId();
        assertEquals(List.of(f.a(2)), holding(f.target));

        rosterService.deleteOverride(f.target.getId(), manualRowId);

        assertNull(seatOf(f.target, 2), "撤销手动覆盖后座位为空");
        assertEquals(StageConstants.SLOT_BYE, entryAt(f.target, 2).getSlotKind());
        assertEquals(List.of(f.a(2)), holding(f.target));

        entryStore.reconcileEntries(f.target.getId());
        assertNull(seatOf(f.target, 2));
        assertTrue(holding(f.target).contains(f.a(2)), "对账后被挤的自动人仍在待落位区");
        assertFalse(holding(f.target).contains(f.m(1)));
    }

    // =====================================================================
    // fixture
    // =====================================================================

    /** 自动来源 A(4 名晋级,名次 1..4;另有名次 5 落选)、手动池 M(3 人)。 */
    private final class Fx {
        TStage autoSource;
        TStage manualSource;
        TStage target;
        final List<Long> autoIds = new ArrayList<>();
        final List<Long> manualIds = new ArrayList<>();

        Long a(int rank) {
            return autoIds.get(rank - 1);
        }

        Long m(int n) {
            return manualIds.get(n - 1);
        }
    }

    /**
     * @param secondAutoEdge 是否再加一条自动边(来源 M 的整单)
     * @param manualEdge     是否加一条手动边(来源 M,不取人)
     */
    private Fx fixture(String name, boolean secondAutoEdge, boolean manualEdge) {
        Fx f = new Fx();
        Long tid = newTournament(name);
        f.autoSource = newStage(tid, "A", "KNOCKOUT", StageConstants.STAGE_SETTLED, 0L);
        f.manualSource = newStage(tid, "M", "KNOCKOUT", StageConstants.STAGE_SETTLED, 0L);
        f.target = newStage(tid, "目标", "KNOCKOUT", StageConstants.STAGE_DRAFT, 8L);
        for (long r = 1; r <= 5; r++) {
            f.autoIds.add(comp(tid, f.autoSource.getId(), r,
                r <= 4 ? OutcomeStatusEnum.ADVANCE : OutcomeStatusEnum.ELIMINATED).getId());
        }
        for (long r = 1; r <= 3; r++) {
            f.manualIds.add(comp(tid, f.manualSource.getId(), r, OutcomeStatusEnum.ADVANCE).getId());
        }
        List<TStageRosterGroupBo> groups = new ArrayList<>();
        TStageRosterGroupBo auto = edge(f.autoSource.getId(), 0);
        auto.setRankStart(1);
        auto.setRankEnd(4);
        groups.add(auto);
        if (secondAutoEdge) {
            groups.add(edge(f.manualSource.getId(), 0));
        }
        if (manualEdge) {
            TStageRosterGroupBo manual = edge(f.manualSource.getId(), 0);
            manual.setFillMode(RosterConstants.FILL_MANUAL);
            groups.add(manual);
        }
        groupStore.saveGroups(f.target, groups);
        entryStore.rebuildEntries(f.target.getId());
        return f;
    }

    // ------------------------------------------------------------------ helpers

    private void override(TStage target, String op, Long sourceCompetitorId, Long seat, String placement) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(op);
        bo.setSourceCompetitorId(sourceCompetitorId);
        bo.setSeedRank(seat);
        bo.setPlacement(placement);
        rosterService.addOverride(target.getId(), bo);
    }

    /** 直接塞一条导播手工行(origin=MANUAL),模拟"之前加过的人" */
    private void insertManualRow(TStage target, Long sourceCompetitorId, Long slot) {
        TCompetitor c = competitorMapper.selectById(sourceCompetitorId);
        TStageRosterEntry e = new TStageRosterEntry();
        e.setTournamentId(target.getTournamentId());
        e.setTargetStageId(target.getId());
        e.setSlot(slot);
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        e.setOrigin(RosterConstants.ENTRY_ORIGIN_MANUAL);
        e.setStatus(RosterConstants.ENTRY_STATUS_READY);
        e.setRefType("SOURCE");
        e.setSourceCompetitorId(sourceCompetitorId);
        e.setSourceStageId(c.getStageId());
        e.setEntryTag(RosterConstants.ENTRY_ADVANCE);
        // 该座位上若是空位行,先挪走,避免同座两行
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, target.getId())
            .eq(TStageRosterEntry::getSlot, slot)
            .isNull(TStageRosterEntry::getSourceCompetitorId));
        entryMapper.insert(e);
    }

    private List<TStageRosterEntry> rows(TStage target) {
        return entryStore.selectEntries(target.getId());
    }

    /** 座位 from..to 上的人(来源参赛方 id);空位为 null,但 List.of 不允许 null,故用 ArrayList */
    private List<Long> seats(TStage target, int from, int to) {
        List<Long> out = new ArrayList<>();
        for (int s = from; s <= to; s++) {
            out.add(seatOf(target, s));
        }
        return out;
    }

    private Long seatOf(TStage target, int slot) {
        return rows(target).stream()
            .filter(e -> Objects.equals(e.getSlot(), (long) slot))
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .findFirst().orElse(null);
    }

    private TStageRosterEntry entryAt(TStage target, int slot) {
        return rows(target).stream().filter(e -> Objects.equals(e.getSlot(), (long) slot)).findFirst().orElseThrow();
    }

    private List<Long> holding(TStage target) {
        return rows(target).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()) && e.getSlot() == null)
            .map(TStageRosterEntry::getSourceCompetitorId)
            .toList();
    }

    private long slotRows(TStage target) {
        return rows(target).stream().filter(e -> e.getSlot() != null).count();
    }

    private List<TStageRosterEntry> rowsOf(TStage target, Long sourceCompetitorId) {
        return rows(target).stream()
            .filter(e -> Objects.equals(e.getSourceCompetitorId(), sourceCompetitorId))
            .toList();
    }

    private static List<Long> sorted(Long... ids) {
        return sortedList(List.of(ids));
    }

    private static List<Long> sortedList(List<Long> ids) {
        return ids.stream().sorted().toList();
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStage newStage(Long tid, String name, String mode, String status, Long start) {
        TStage s = new TStage();
        s.setTournamentId(tid);
        s.setName(name);
        s.setStageMode(mode);
        s.setStatus(status);
        s.setTeamCountStart(start);
        stageMapper.insert(s);
        return s;
    }

    private TCompetitor comp(Long tid, Long stageId, Long finalRank, OutcomeStatusEnum outcome) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName("S" + stageId + "R" + finalRank);
        c.setNumber(String.valueOf(finalRank));
        c.setFinalRank(finalRank);
        c.setOutcomeStatus(outcome.getCode());
        competitorMapper.insert(c);
        return c;
    }

    private static TStageRosterGroupBo edge(Long sourceStageId, int quota) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(RosterConstants.FILTER_ANY);
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(quota);
        return g;
    }
}
