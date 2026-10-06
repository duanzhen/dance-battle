package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterMoveBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条来源汇合到同一赛段时,实时落座不能撞座、不能把人挤掉。
 *
 * <p>回归的事故:实时写入用的是「来源赛段内的名次 = 目标赛段座位号」。这个等式只在
 * 「单一淘汰赛来源、名次没有空洞」时成立。多入口汇合时两条来源的名次都从 1 开始,
 * 后写的人会把先坐进去的人从座位上覆盖掉,现场表现就是中间态里少人/重复。</p>
 *
 * <p>现在的口径:单入口仍然自动排座(座位映射与整表重建同源,不再撞座);
 * 多入口(两个及以上不同来源赛段)不做任何座位计算,人先进待落位区,
 * 由导播在中间态拖到真实座位,落位前不允许确认名单。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class MergePreAdvanceSeatTest {

    private static final String DB_PATH = "target/merge-pre-advance-seat.db";

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
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private com.dance.street.game.mapper.TStageMapper stageMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITStageRosterService rosterService;

    /**
     * 多入口汇合:人不自动排座,全部进待落位区;未落位不能确认名单;拖到座位后正常装配。
     *
     * <p>回归的事故:两条分支各自从第 1 名开始,按"名次=座位"自动落座会把 A 的胜者
     * 覆盖掉。现在多入口不做座位计算,系统只负责把人凑齐,坐哪由导播拖。</p>
     */
    @Test
    void multiEntryGoesToHoldingAreaAndConfirmWaitsForPlacement() {
        Long tid = newTournament("merge-holding-area");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        // 决赛的名单来源 = 默认(半决赛B 整单晋级) + 半决赛A 整单晋级,两条分支汇合
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());

        // A 线先判完:人先进待落位区(没有座位号),不占任何座位
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        assertEquals(List.of(winnerA), holdingCompetitorIds(finals.getId()),
            "A 线胜者先进待落位区");
        assertTrue(seatedSlots(finals.getId()).isEmpty(), "多入口不做自动落座");

        // B 线判完:两个人都在待落位区,一个都不能丢
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        List<Long> holding = holdingCompetitorIds(finals.getId());
        assertEquals(2, holding.size(), "两条分支的胜者都要在待落位区,实际=" + holding);
        assertTrue(holding.containsAll(List.of(winnerA, winnerB)),
            "两个胜者都不能丢,实际=" + holding);

        // 大屏「参赛选手」控件:没物化时读中间态,未落位的人也要在返回里
        StageParticipantsVo pre = rosterService.listStageParticipants(finals.getId());
        assertEquals("ROSTER", pre.getSource(), "未确认名单应读中间态");
        assertEquals(2, pre.getItems().size(), "参赛选手应包含还没落位的人");
        assertEquals(2, pre.getHoldingCount());
        assertTrue(pre.getItems().stream().allMatch(i -> Boolean.TRUE.equals(i.getHolding())));

        // 来源全部结算,名单就绪;没落位之前不能确认
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());
        assertEquals(Boolean.TRUE, rosterService.previewAssembled(finals.getId()).getReady(),
            "两条来源都结算后名单就绪");
        assertThrows(ServiceException.class, () -> rosterService.applyRoster(finals.getId(), null),
            "还有人没落位,不能确认名单");

        // 导播把两人拖到 1、2 号座位
        List<TStageRosterEntry> rows = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .toList();
        List<TStageRosterOrderBo.Item> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            TStageRosterOrderBo.Item it = new TStageRosterOrderBo.Item();
            it.setOverrideId(rows.get(i).getId());
            it.setSeedRank((long) (i + 1));
            items.add(it);
        }
        rosterService.reorderRoster(finals.getId(), items);
        assertEquals(2, seatedSlots(finals.getId()).size(), "拖动后两人各占一个座位");
        assertTrue(holdingCompetitorIds(finals.getId()).isEmpty(), "待落位区应清空");

        // 落位完成才能确认进下一赛段
        assertEquals(2, rosterService.applyRoster(finals.getId(), null));

        // 物化后切换成真实参赛选手(有座位号、不再是待落位)
        StageParticipantsVo post = rosterService.listStageParticipants(finals.getId());
        assertEquals("COMPETITOR", post.getSource(), "确认名单后应读真实参赛方");
        assertEquals(2, post.getItems().size());
        assertEquals(0, post.getHoldingCount());
        assertTrue(post.getItems().stream()
                .allMatch(i -> i.getSeedRank() != null && !Boolean.TRUE.equals(i.getHolding())),
            "物化后每个人都应有座位号");
    }

    /**
     * 把待落座的人"钉"到某个座位(SEED):目标座位是空位时直接换进来,不能留下同座两行。
     *
     * <p>回归:P1-2 —— 被钉的人在待落座(slot=null)时,空位占位者的 slot 被设成 null,
     * 而 updateById 跳过 null 字段,占位者仍占着原座位,于是同一座位出现两行。</p>
     */
    @Test
    void pinHoldingPersonToSeatDoesNotDuplicateSlot() {
        Long tid = newTournament("pin-holding");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());
        assertEquals(2, holdingCompetitorIds(finals.getId()).size(), "两人先在待落座");

        // 把 B 钉到 1 号位(该座位此时是空位实体行)
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_SEED);
        bo.setSourceCompetitorId(winnerB);
        bo.setSeedRank(1L);
        rosterService.addOverride(finals.getId(), bo);

        assertEquals(List.of(winnerB),
            rosterService.entriesOf(finals.getId()).stream()
                .filter(e -> Long.valueOf(1L).equals(e.getSlot()))
                .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
                .map(TStageRosterEntry::getSourceCompetitorId).toList(),
            "钉座位后 1 号位就是被钉的人");
        List<Long> slots = rosterService.entriesOf(finals.getId()).stream()
            .map(TStageRosterEntry::getSlot).filter(Objects::nonNull).toList();
        assertEquals(slots.size(), new HashSet<>(slots).size(), "不能出现同一座位两行:" + slots);
        assertEquals(List.of(winnerA), holdingCompetitorIds(finals.getId()),
            "另一个人仍在待落座");
    }

    /**
     * 把已落位的人拖回待落位区,必须真的把座位号清掉。
     *
     * <p>回归事故:落库走的是 {@code updateById},MyBatis-Plus 默认跳过 null 字段,
     * {@code slot=null} 根本写不进去 —— 人被拖走后还占着原座位,而其余人被重新编号后
     * 顶到了同一个座位。现场表现就是"拖进去的那个人当场消失,之后再怎么拖都没反应"。</p>
     */
    @Test
    void draggingSeatedPersonBackToHoldingClearsSeat() {
        Long tid = newTournament("drag-to-holding");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 4L, 2L, semi.getId());

        initializeAndGenerate(semi.getId());
        for (TMatch m : matchesOf(semi.getId())) {
            matchResultService.startMatch(m.getId());
            submitLeftWin(m);
        }
        lifecycleService.completeStage(semi.getId());

        List<TStageRosterEntry> seated = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .sorted(Comparator.comparing(TStageRosterEntry::getSlot))
            .toList();
        assertEquals(2, seated.size(), "半决赛结算后两人落进决赛名单");

        // 把 2 号位的人拖回待落位区(前端会把剩下的人重新编号成 1..N)
        TStageRosterOrderBo.Item keep = new TStageRosterOrderBo.Item();
        keep.setOverrideId(seated.get(0).getId());
        keep.setSeedRank(1L);
        TStageRosterOrderBo.Item toHolding = new TStageRosterOrderBo.Item();
        toHolding.setOverrideId(seated.get(1).getId());
        toHolding.setHolding(true);
        rosterService.reorderRoster(finals.getId(), List.of(keep, toHolding));

        TStageRosterEntry moved = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> Objects.equals(e.getId(), seated.get(1).getId()))
            .findFirst().orElseThrow();
        assertNull(moved.getSlot(), "拖回待落位区的人不能还占着座位号");

        List<Long> slots = seatedSlots(finals.getId());
        assertEquals(1, slots.size(), "座位上只剩没被拖走的那个人,实际=" + slots);
        assertEquals(slots.size(), new HashSet<>(slots).size(), "不能出现两个人挤同一个座位");
        assertEquals(List.of(seated.get(1).getSourceCompetitorId()), holdingCompetitorIds(finals.getId()),
            "被拖走的人应该在待落位区");
    }

    /**
     * 「败者去向」来源组(出口面板的 结果=败者)带进来的人,不能被胜者的实时同步当成
     * "不再晋级"清掉。回归事故:实时写入只认 ADVANCE 的人,同一个来源里由败者组占的
     * 座位会被这次同步还原成空位,中间态里那个人当场消失(要等整赛段结算才回来)。
     */
    @Test
    void loserGroupRowsAreNotWipedByWinnerSync() {
        Long tid = newTournament("loser-group-row");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 4L, 2L, semi.getId());
        // 决赛:胜者走默认组,败者另走一条「落选」来源组
        addGroup(finals.getId(), semi.getId(), OutcomeStatusEnum.ELIMINATED.getCode());

        initializeAndGenerate(semi.getId());
        TMatch m1 = matchesOf(semi.getId()).get(0);
        matchResultService.startMatch(m1.getId());
        Long winner = submitLeftWin(m1);

        List<Long> seated = seatedCompetitorIds(finals.getId());
        assertEquals(2, seated.size(), "胜者与败者都要留在决赛中间态,实际=" + seated);
        assertTrue(seated.contains(winner), "胜者要在,实际=" + seated);
    }

    /**
     * 中间态按来源边放行:某条来源结算后,它带进来的人立刻能调整,不必等另一条来源也跑完。
     *
     * <p>多入口汇合(待落位)按"座位靠导播拖"的口径全放开:来源还没结算时,来自它的人也能先排位——
     * 实时对账与结算重建都只补人/取人,不会重排已落好的行;行上仍标「未结算」提示后续可能变化。</p>
     *
     * <p>对照:单入口自动排座的赛段仍按来源结算加锁(见 PreAdvanceRealtimeTest)。</p>
     */
    @Test
    void mergeRowsStayAdjustableWhileSourceStillRunning() {
        Long tid = newTournament("partial-source-adjustable");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 4L, 1L, semiB.getId());
        // 决赛来源 = 默认(半决赛B 整单晋级) + 半决赛A 整单晋级,两条边汇合
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());

        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));

        // A 段还没结算:多入口汇合不自动排座,来自 A 的人可以马上拖到座位上
        assertEquals(Boolean.TRUE, adjustableOf(finals.getId(), winnerA),
            "多入口汇合不按来源结算锁行");
        assertEquals(Boolean.TRUE, sourcePendingOf(finals.getId(), winnerA),
            "来源未结算仍要标记出来(选手后续可能变化)");
        rosterService.moveRosterRow(finals.getId(), moveBo(winnerA, 1L));
        assertEquals(1L, entryOfSource(finals.getId(), winnerA).getSlot(), "来源未结算也能先排位");

        // A 段结算:已经排好的位置不能被重排(此时 B 还在跑、名单整体仍未就绪)
        lifecycleService.completeStage(semiA.getId());
        assertFalse(Boolean.TRUE.equals(rosterService.previewAssembled(finals.getId()).getReady()),
            "B 还没结算,整单尚未就绪");
        assertEquals(1L, entryOfSource(finals.getId(), winnerA).getSlot(), "A 结算后位置要保留");

        // B 段同样:先拖到 2 号位,再移出(留可撤销的「移出」标记)
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        assertEquals(Boolean.TRUE, adjustableOf(finals.getId(), winnerB), "B 未结算也能先排位");
        rosterService.moveRosterRow(finals.getId(), moveBo(winnerB, 2L));
        assertEquals(2L, entryOfSource(finals.getId(), winnerB).getSlot());
        removeOverride(finals.getId(), winnerB);
        assertEquals(StageConstants.SLOT_BYE, entryOfSource(finals.getId(), winnerB).getSlotKind(),
            "来源未结算也能移出(留可撤销标记)");

        // B 结算:两条来源都定案,但导播的排位与「移出」都不能被结算重建冲掉
        lifecycleService.completeStage(semiB.getId());
        assertEquals(1L, entryOfSource(finals.getId(), winnerA).getSlot(), "结算后 A 的人仍在 1 号位");
        assertEquals(StageConstants.SLOT_BYE, entryOfSource(finals.getId(), winnerB).getSlotKind(),
            "结算不会把「移出」标记放回来");
    }

    /**
     * 两种重建口径的分界:导播点「恢复自动顺序」是<b>显式重建</b>——整表按投影重来,
     * 多入口汇合下人工排位一并清空(全体回待落位区);而上游结算/重判引发的
     * <b>对账重建</b>必须保留导播已排好的位置。
     */
    @Test
    void explicitRebuildDiscardsManualPlacementButSettleReconcileKeepsIt() {
        Long tid = newTournament("explicit-rebuild-vs-reconcile");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 4L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());

        // 导播把 A 的晋级者拖到 1 号位
        rosterService.moveRosterRow(finals.getId(), moveBo(winnerA, 1L));
        assertEquals(1L, entryOfSource(finals.getId(), winnerA).getSlot());

        // 「恢复自动顺序」= 显式重建:人工排位清空,人回到待落位区
        rosterService.rebuildEntries(finals.getId());
        assertNull(entryOfSource(finals.getId(), winnerA).getSlot(),
            "显式重建要丢弃人工排位");

        // 再排一次,由来源 B 结算触发对账重建:位置必须保留
        rosterService.moveRosterRow(finals.getId(), moveBo(winnerA, 1L));
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiB.getId());
        assertEquals(1L, entryOfSource(finals.getId(), winnerA).getSlot(),
            "对账重建要保留导播排好的位置");
    }

    /**
     * 加人可以直接加到「待落座区」(不占座位号),之后再拖到座位。
     */
    @Test
    void addGuestCanGoStraightToHoldingArea() {
        Long tid = newTournament("add-to-holding");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());
        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        submitLeftWin(matchesOf(semiA.getId()).get(0));
        submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());

        TStageRosterOverrideBo g = new TStageRosterOverrideBo();
        g.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        g.setGuestName("待落座外卡");
        g.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        rosterService.addOverride(finals.getId(), g);

        TStageRosterEntry guest = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> "待落座外卡".equals(e.getGuestName()))
            .findFirst().orElse(null);
        assertTrue(guest != null, "应写入外卡行");
        assertNull(guest.getSlot(), "加到待落座区不应占座位号");
        assertEquals(StageConstants.SLOT_PLAYER, guest.getSlotKind());
        assertEquals(3, holdingCompetitorIdsCount(finals.getId()), "待落座区应有 3 行(两条来源 + 外卡)");

        // 拖到 1 号座位
        TStageRosterMoveBo mv = new TStageRosterMoveBo();
        mv.setOverrideId(guest.getId());
        mv.setTargetSeed(1L);
        rosterService.moveRosterRow(finals.getId(), mv);
        TStageRosterEntry seated = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> Objects.equals(e.getId(), guest.getId())).findFirst().orElse(null);
        assertTrue(seated != null && seated.getSlot() != null, "拖到座位后应有座位号");
        assertEquals(1L, seated.getSlot().longValue());
    }

    /**
     * 来源入边还有没结束时:多入口汇合仍然可以先加人(待落座),不再被整单挡住。
     *
     * <p>回归:多入口汇合里只有一条来源结算了,加外卡/手工拉人原来会被"上一赛段还没结束"整单挡住;
     * 待落座区不占座位号、不参与自动排座,来源没结束也允许先放进来,来源定案后再由导播拖到座位。</p>
     */
    @Test
    void addToHoldingAllowedWhileSomeSourcesUnsettled() {
        Long tid = newTournament("holding-unsettled");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        // A 线判完并结算;B 线还在打 -> 来源入边有未结束
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        assertEquals(List.of(winnerA), holdingCompetitorIds(finals.getId()),
            "A 线胜者先在待落座区");

        // 加到待落座区:来源入边还没结束也允许
        TStageRosterOverrideBo holdingGuest = new TStageRosterOverrideBo();
        holdingGuest.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        holdingGuest.setGuestName("待落座外卡");
        holdingGuest.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        rosterService.addOverride(finals.getId(), holdingGuest);

        TStageRosterEntry guest = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> "待落座外卡".equals(e.getGuestName())).findFirst().orElse(null);
        assertTrue(guest != null, "应写入外卡行");
        assertNull(guest.getSlot(), "加到待落座区不应占座位号");
        assertEquals(2, holdingCompetitorIdsCount(finals.getId()),
            "待落座区应有 2 行(A 线胜者 + 外卡)");

        // 从未结算来源手工拉人:加到待落座区也可以(多入口汇合不按来源加锁)
        TCompetitor someoneB = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, semiB.getId()).orderByAsc(TCompetitor::getId)).get(0);
        TStageRosterOverrideBo pullHolding = new TStageRosterOverrideBo();
        pullHolding.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        pullHolding.setSourceCompetitorId(someoneB.getId());
        pullHolding.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        rosterService.addOverride(finals.getId(), pullHolding);
        assertTrue(holdingCompetitorIds(finals.getId()).contains(someoneB.getId()),
            "从没结算来源拉进来的人可先放待落座区");

        // 另一条来源结算触发的重建:导播先放进来的行不能被冲掉
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiB.getId());
        List<Long> holding = holdingCompetitorIds(finals.getId());
        assertTrue(holding.containsAll(List.of(winnerA, winnerB)), "两条来源的胜者都还在,实际=" + holding);
        assertTrue(rosterService.entriesOf(finals.getId()).stream()
                .anyMatch(e -> "待落座外卡".equals(e.getGuestName())),
            "重建后外卡仍应在待落座区");
    }

    /**
     * 回归:之前被移出的人「重新加入」时,必须按调用方指定的实际座位落位,
     * 而不是恢复成这一行原来带的座位(来源备份原座号/旧座位)。
     */
    @Test
    void reAddRemovedPersonHonorsRequestedSeat() {
        Long tid = newTournament("re-add-seat");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());
        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());

        assertNull(entryOfSource(finals.getId(), winnerA).getSlot(), "汇合段的人先在待落座区");
        removeOverride(finals.getId(), winnerA);

        TStageRosterOverrideBo add = new TStageRosterOverrideBo();
        add.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        add.setSourceCompetitorId(winnerA);
        add.setSeedRank(2L);
        add.setPlacement("REPLACE");
        rosterService.addOverride(finals.getId(), add);

        TStageRosterEntry row = entryOfSource(finals.getId(), winnerA);
        assertEquals(2L, row.getSlot().longValue(),
            "重新加入应按指定的实际座位落位,而不是回到旧位置");
        assertEquals(StageConstants.SLOT_PLAYER, row.getSlotKind());
    }

    // ===== 工具 =====

    private record MergeWinners(TStageVo finals, Long winnerA, Long winnerB) {
    }

    /** 造一个"两分支汇合 + 两来源都已结算"的决赛,返回两个胜者;人先在待落座区。 */
    private MergeWinners buildSettledMerge(String name) {
        Long tid = newTournament(name);
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());
        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());
        return new MergeWinners(finals, winnerA, winnerB);
    }

    private void seat(Long stageId, Long sourceCompetitorId, long seed) {
        rosterService.moveRosterRow(stageId, moveBo(sourceCompetitorId, seed));
    }

    /**
     * 加人「替换」占用某座位:原占位者被挤到待落座区(不删除),新人坐进该座位。
     */
    @Test
    void addByReplaceDisplacesOccupantToHolding() {
        MergeWinners m = buildSettledMerge("replace-displace");
        seat(m.finals().getId(), m.winnerA(), 1L);
        seat(m.finals().getId(), m.winnerB(), 2L);
        assertEquals(2, seatedSlots(m.finals().getId()).size(), "两人先坐满");

        TStageRosterOverrideBo g = new TStageRosterOverrideBo();
        g.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        g.setGuestName("替换外卡");
        g.setSeedRank(1L);
        g.setPlacement("REPLACE");
        rosterService.addOverride(m.finals().getId(), g);

        TStageRosterEntry guest = rosterService.entriesOf(m.finals().getId()).stream()
            .filter(e -> "替换外卡".equals(e.getGuestName())).findFirst().orElse(null);
        assertTrue(guest != null && guest.getSlot() != null, "新人应有座位");
        assertEquals(1L, guest.getSlot().longValue(), "新人坐进 1 号座位");
        assertEquals(List.of(m.winnerA()), holdingCompetitorIds(m.finals().getId()),
            "原 1 号位的人进待落座区,而不是被删除");
        assertEquals(2L, entryOfSource(m.finals().getId(), m.winnerB()).getSlot().longValue(),
            "没被顶到的人不动");
    }

    /**
     * 加人「顶位(INSERT)」插在最前:后面的人整体后移,超出计划规模的末尾进待落座区(不删除)。
     */
    @Test
    void addByInsertPushesTailToHolding() {
        MergeWinners m = buildSettledMerge("insert-overflow");
        seat(m.finals().getId(), m.winnerA(), 1L);
        seat(m.finals().getId(), m.winnerB(), 2L);

        TStageRosterOverrideBo g = new TStageRosterOverrideBo();
        g.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        g.setGuestName("顶位外卡");
        g.setSeedRank(1L);
        g.setPlacement(RosterConstants.PLACEMENT_INSERT);
        rosterService.addOverride(m.finals().getId(), g);

        TStageRosterEntry guest = rosterService.entriesOf(m.finals().getId()).stream()
            .filter(e -> "顶位外卡".equals(e.getGuestName())).findFirst().orElse(null);
        assertTrue(guest != null && guest.getSlot() != null, "新人应有座位");
        assertEquals(1L, guest.getSlot().longValue(), "新人插到 1 号位");
        assertEquals(2L, entryOfSource(m.finals().getId(), m.winnerA()).getSlot().longValue(),
            "原 1 号位的人后移一位到 2");
        assertEquals(List.of(m.winnerB()), holdingCompetitorIds(m.finals().getId()),
            "超出计划规模的末尾进待落座区,而不是被删除");
    }

    /**
     * 移动接口:前端只说"把谁移到哪",后端落位并把最新名单整份返回。
     *
     * <p>覆盖:移到座位(返回名单里就在那个座位)→ 再移一个人落位 → 移回待落位区
     * (座位还原成空位行,其他座位不动)。</p>
     */
    @Test
    void moveRowAppliesIntentAndReturnsLatestRoster() {
        Long tid = newTournament("roster-move-intent");
        TStageVo semiA = newStage(tid, "半决赛A", 2L, 1L, null);
        insertCompetitors(tid, semiA.getId(), 2);
        TStageVo semiB = newStage(tid, "半决赛B", 2L, 1L, semiA.getId());
        insertCompetitors(tid, semiB.getId(), 2);
        TStageVo finals = newStage(tid, "决赛", 4L, 1L, semiB.getId());
        addAdvanceGroup(finals.getId(), semiA.getId());

        initializeAndGenerate(semiA.getId());
        initializeAndGenerate(semiB.getId());
        matchResultService.startMatch(matchesOf(semiA.getId()).get(0).getId());
        matchResultService.startMatch(matchesOf(semiB.getId()).get(0).getId());
        Long winnerA = submitLeftWin(matchesOf(semiA.getId()).get(0));
        Long winnerB = submitLeftWin(matchesOf(semiB.getId()).get(0));
        lifecycleService.completeStage(semiA.getId());
        lifecycleService.completeStage(semiB.getId());
        assertEquals(2, holdingCompetitorIds(finals.getId()).size(), "汇合赛段两人都先待落位");

        // 把 A 移到 1 号座位:返回值就是移动后的最新名单
        RosterPreviewVo afterMove = rosterService.moveRosterRow(finals.getId(), moveBo(winnerA, 1L));
        assertEquals(1L, itemOf(afterMove, winnerA).getSeedRank(), "返回的名单里 A 已在 1 号座位");
        assertNull(itemOf(afterMove, winnerB).getSeedRank(), "B 仍在待落位区");
        assertEquals(List.of(1L), seatedSlots(finals.getId()), "落库结果与返回一致");

        // B 移到 2 号座位
        afterMove = rosterService.moveRosterRow(finals.getId(), moveBo(winnerB, 2L));
        assertEquals(2L, itemOf(afterMove, winnerB).getSeedRank());
        assertTrue(holdingCompetitorIds(finals.getId()).isEmpty(), "两人都落位");

        // 把 A 移回待落位区:座位 1 还原成空位行,座位 2 的人不动
        afterMove = rosterService.moveRosterRow(finals.getId(), moveBo(winnerA, null));
        assertNull(itemOf(afterMove, winnerA).getSeedRank(), "A 回到待落位区");
        assertEquals(List.of(1L, 2L, 3L, 4L), rosterService.entriesOf(finals.getId()).stream()
            .map(TStageRosterEntry::getSlot).filter(Objects::nonNull).sorted().toList(),
            "座位按计划人数铺满 1..4,空位照占号");
        assertEquals(List.of(winnerA), holdingCompetitorIds(finals.getId()), "A 回到待落位区");
        assertEquals(List.of(winnerB), rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> e.getSlot() != null)
            .map(TStageRosterEntry::getSourceCompetitorId).toList(),
            "只有 B 还占着座位");
    }

    /**
     * 来源给的座号超出本赛段容量:不能复制到 slot(会在计划外铺座位,签表/对阵里根本看不见这个人),
     * 应该整份进待落座区,由导播决定去留。
     */
    @Test
    void advancerBeyondCapacityGoesToHolding() {
        Long tid = newTournament("overflow-to-holding");
        TStageVo semi = newStage(tid, "半决赛", 4L, 4L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());   // 只容 2 人

        // 半决赛结算:4 人全部晋级,名次 1..4
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, semi.getId()).orderByAsc(TCompetitor::getId));
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            c.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
            c.setFinalRank((long) (i + 1));
            competitorMapper.updateById(c);
        }
        TStage settled = new TStage();
        settled.setId(semi.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        rosterService.rebuildEntries(finals.getId());
        assertEquals(List.of(1L, 2L), seatedSlots(finals.getId()), "只坐得下 2 人");
        assertEquals(2, holdingCompetitorIds(finals.getId()).size(), "超出的两人进待落座区");
        assertEquals(2L, rosterService.entriesOf(finals.getId()).stream()
            .map(TStageRosterEntry::getSlot).filter(Objects::nonNull).distinct().count(),
            "座位不超出计划规模");
    }

    /**
     * 海选/排名赛 + 单入口:名次是"按名额累加的全局序号"(如某条出口取 9~24 名共 16 人),
     * 落座时整体下移 min-1,把前面的空档压掉 → 座位 1..16;source_slot 保留 9~24。
     */
    @Test
    void auditionRankRangeIsShiftedToTargetSeats() {
        Long tid = newTournament("audition-rank-shift");
        TStageVo audition = newStageOfMode(tid, "海选", "AUDITION", 0L, 16L, null);
        insertCompetitors(tid, audition.getId(), 24);
        TStageVo finals = newStage(tid, "16强", 16L, 1L, audition.getId());
        useSingleRankRangeExit(finals.getId(), audition.getId(), 9, 24);
        setRanksAndSettle(audition.getId(), rank -> rank >= 9 && rank <= 24);

        rosterService.rebuildEntries(finals.getId());
        List<Long> seats = seatedSlots(finals.getId());
        assertEquals(16, seats.size(), "16 人都要落座");
        assertEquals(1L, seats.stream().mapToLong(Long::longValue).min().orElse(0));
        assertEquals(16L, seats.stream().mapToLong(Long::longValue).max().orElse(0));
        assertTrue(holdingCompetitorIds(finals.getId()).isEmpty(), "不产生待落座");

        List<Long> sourceSlots = sourceSlotsOf(finals.getId());
        assertEquals(16, sourceSlots.size());
        assertEquals(9L, sourceSlots.stream().mapToLong(Long::longValue).min().orElse(0),
            "source_slot 保留来源原名次");
        assertEquals(24L, sourceSlots.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    /**
     * 正常晋级(取 1~8 名)不受压紧影响:最小名次是 1 → 位移 0,
     * 座位号与"原样复制来源座号"完全一致,不会多出待落座的人。
     */
    @Test
    void normalRankRangeStartingAtOneIsUnchanged() {
        Long tid = newTournament("audition-rank-normal");
        TStageVo audition = newStageOfMode(tid, "海选", "AUDITION", 0L, 8L, null);
        insertCompetitors(tid, audition.getId(), 16);
        TStageVo finals = newStage(tid, "8强", 8L, 1L, audition.getId());
        useSingleRankRangeExit(finals.getId(), audition.getId(), 1, 8);
        setRanksAndSettle(audition.getId(), rank -> rank <= 8);

        rosterService.rebuildEntries(finals.getId());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L),
            seatedSlots(finals.getId()).stream().sorted().toList(), "座位号就是原名次");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), sourceSlotsOf(finals.getId()));
        assertTrue(holdingCompetitorIds(finals.getId()).isEmpty(), "没有多余的人进待落座");
    }

    /** 中间夹着的空档不压:名次 3/4/7 → 座位 1/2/5(不是压成 1/2/3)。 */
    @Test
    void shiftingKeepsMiddleHoles() {
        Long tid = newTournament("audition-rank-holes");
        TStageVo audition = newStageOfMode(tid, "海选", "AUDITION", 0L, 16L, null);
        insertCompetitors(tid, audition.getId(), 24);
        TStageVo finals = newStage(tid, "16强", 16L, 1L, audition.getId());
        useSingleRankRangeExit(finals.getId(), audition.getId(), 1, 24);
        setRanksAndSettle(audition.getId(), rank -> rank == 3 || rank == 4 || rank == 7);

        rosterService.rebuildEntries(finals.getId());
        assertEquals(List.of(1L, 2L, 5L), seatedSlots(finals.getId()), "只压掉前面的空档,中间空档保留");
        assertEquals(List.of(3L, 4L, 7L), sourceSlotsOf(finals.getId()));
    }

    /** 换成"取 [rankStart,rankEnd] 名"的单条出口(先加新的,再删系统补的默认衔接) */
    private void useSingleRankRangeExit(Long targetStageId, Long sourceStageId, int rankStart, int rankEnd) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(0);
        g.setRankStart(rankStart);
        g.setRankEnd(rankEnd);
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setGroups(List.of(g));
        rosterService.addGroups(targetStageId, bo);
        // 海选按圈生成的出口是 generated=0(固定边),不能按 generated 判断,按"不是我加的这条"删
        for (TStageRosterGroupBo x : rosterService.groupsOfStage(targetStageId)) {
            boolean mine = x.getZone() == null && Objects.equals(x.getRankStart(), rankStart)
                && Objects.equals(x.getRankEnd(), rankEnd);
            if (!mine) {
                rosterService.removeGroup(targetStageId, x.getId());
            }
        }
    }

    /** 给来源赛段所有人按顺序写名次 1..N 与结果,然后整段置为 SETTLED */
    private void setRanksAndSettle(Long stageId, java.util.function.IntPredicate advance) {
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId).orderByAsc(TCompetitor::getId));
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            int rank = i + 1;
            c.setFinalRank((long) rank);
            c.setOutcomeStatus(advance.test(rank) ? OutcomeStatusEnum.ADVANCE.getCode()
                : OutcomeStatusEnum.ELIMINATED.getCode());
            competitorMapper.updateById(c);
        }
        TStage settled = new TStage();
        settled.setId(stageId);
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);
    }

    /** 已落座行按座位排序后的"来源原名次" */
    private List<Long> sourceSlotsOf(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .sorted(Comparator.comparingLong(e -> e.getSlot() == null ? Long.MAX_VALUE : e.getSlot()))
            .map(TStageRosterEntry::getSourceSlot)
            .toList();
    }

    private TStageRosterMoveBo moveBo(Long sourceCompetitorId, Long targetSeed) {
        TStageRosterMoveBo bo = new TStageRosterMoveBo();
        bo.setSourceCompetitorId(sourceCompetitorId);
        bo.setTargetSeed(targetSeed);
        bo.setToHolding(targetSeed == null);
        return bo;
    }

    /** 预览里某个人对应的条目 */
    private RosterPreviewItemVo itemOf(RosterPreviewVo preview, Long sourceCompetitorId) {
        return preview.getItems().stream()
            .filter(i -> Objects.equals(i.getSourceCompetitorId(), sourceCompetitorId))
            .findFirst().orElse(null);
    }

    /** 该行现在能不能调整(取自中间态预览的 adjustable 标记) */
    private Boolean adjustableOf(Long stageId, Long sourceCompetitorId) {
        RosterPreviewVo preview = rosterService.previewAssembled(stageId);
        return preview.getItems().stream()
            .filter(i -> Objects.equals(i.getSourceCompetitorId(), sourceCompetitorId))
            .map(RosterPreviewItemVo::getAdjustable)
            .findFirst().orElse(null);
    }

    /** 该行是否标记「来源还没结算」(取自中间态预览的 sourcePending 标记) */
    private Boolean sourcePendingOf(Long stageId, Long sourceCompetitorId) {
        RosterPreviewVo preview = rosterService.previewAssembled(stageId);
        return preview.getItems().stream()
            .filter(i -> Objects.equals(i.getSourceCompetitorId(), sourceCompetitorId))
            .map(RosterPreviewItemVo::getSourcePending)
            .findFirst().orElse(null);
    }

    private TStageRosterEntry entryOfSource(Long stageId, Long sourceCompetitorId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> Objects.equals(e.getSourceCompetitorId(), sourceCompetitorId))
            .findFirst().orElse(null);
    }

    private void removeOverride(Long stageId, Long sourceCompetitorId) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_REMOVE);
        bo.setSourceCompetitorId(sourceCompetitorId);
        rosterService.addOverride(stageId, bo);
    }

    /** 判该场为"左槽胜",返回胜者(左槽选手)ID;场次需已开始 */
    private Long submitLeftWin(TMatch match) {
        List<TMatchParticipant> rows = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertEquals(2, rows.size(), match.getName() + " 应有两个座位");
        Long left = rows.get(0).getCompetitorId();
        Long right = rows.get(1).getCompetitorId();
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(left, "WIN");
        outcomes.put(right, "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
        return left;
    }

    private List<Long> seatedCompetitorIds(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .toList();
    }

    private List<Long> seatedSlots(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSlot)
            .filter(Objects::nonNull)
            .toList();
    }

    /** 待落位区的人(PLAYER 行但没有座位号) */
    private List<Long> holdingCompetitorIds(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> e.getSlot() == null)
            .map(TStageRosterEntry::getSourceCompetitorId)
            .toList();
    }

    /** 待落位区的行数(含无来源的外卡,故按行数而不是按来源参赛方统计) */
    private long holdingCompetitorIdsCount(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> e.getSlot() == null)
            .count();
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
    }

    private void addAdvanceGroup(Long targetStageId, Long sourceStageId) {
        addGroup(targetStageId, sourceStageId, OutcomeStatusEnum.ADVANCE.getCode());
    }

    private void addGroup(Long targetStageId, Long sourceStageId, String resultFilter) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(resultFilter);
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(0);
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setGroups(List.of(g));
        rosterService.addGroups(targetStageId, bo);
    }

    private void initializeAndGenerate(Long stageId) {
        InitializeStageBo init = new InitializeStageBo();
        init.setStageId(stageId);
        lifecycleService.initialize(init);
        GenerateMatchesBo gen = new GenerateMatchesBo();
        gen.setStageId(stageId);
        lifecycleService.generateMatches(gen);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, Long start, Long end, Long afterStageId) {
        return newStageOfMode(tournamentId, name, "KNOCKOUT", start, end, afterStageId);
    }

    private TStageVo newStageOfMode(Long tournamentId, String name, String mode,
                                    Long start, Long end, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"" + mode + "\",\"knockout\":{\"teamsCount\":" + start
            + ",\"advanceCount\":" + end + ",\"pairingMode\":\"SEED\"}}");
        return stageService.insertByBo(bo);
    }

    private void insertCompetitors(Long tournamentId, Long stageId, int count) {
        for (int i = 1; i <= count; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tournamentId);
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("P" + i);
            c.setNumber(String.valueOf(i));
            c.setSeedRank((long) i);
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
        }
    }
}
