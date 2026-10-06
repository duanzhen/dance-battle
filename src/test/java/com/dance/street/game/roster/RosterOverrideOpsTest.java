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
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.bo.TStageRosterRemoveBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间态名单的人工调整:编辑外卡、撤销覆盖、移出(留空位/顶上一位)、批量撤销、加人校验。
 *
 * <p>这些是导播在中间态常用的行级操作,之前没有专门用例,只被"加人/拖动"的用例顺带走到;
 * 这里把 updateOverride / deleteOverride(s) / removeRows 这些分支补齐。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class RosterOverrideOpsTest {

    private static final String DB_PATH = "target/roster-override-ops.db";

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
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private ITStageRosterService rosterService;

    /** 单入口淘汰赛:半决赛(4→2)已结算,决赛名单里两名晋级者已自动落座。 */
    private record Fixture(TStageVo finals, TStageVo semi, Long adv1, Long adv2) {
    }

    private Fixture settledFinals(String name) {
        Long tid = newTournament(name);
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());
        initializeAndGenerate(semi.getId());
        List<TMatch> matches = matchesOf(semi.getId());
        Long a1 = judgeLeftWin(matches.get(0));
        Long a2 = judgeLeftWin(matches.get(1));
        lifecycleService.completeStage(semi.getId());
        return new Fixture(finals, semi, a1, a2);
    }

    private TStageRosterEntry entryOfSource(Long stageId, Long sourceCompetitorId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> Objects.equals(e.getSourceCompetitorId(), sourceCompetitorId))
            .findFirst().orElse(null);
    }

    private TStageRosterEntry entryOfGuest(Long stageId, String guestName) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> guestName.equals(e.getGuestName()))
            .findFirst().orElse(null);
    }

    private Long addGuest(Long stageId, String name, String placement) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        bo.setGuestName(name);
        bo.setPlacement(placement);
        rosterService.addOverride(stageId, bo);
        return entryOfGuest(stageId, name).getId();
    }

    // ===================== updateOverride =====================

    /** 外卡改名/改类型/改号码/改备注。 */
    @Test
    void updateOverrideEditsGuestFields() {
        Fixture f = settledFinals("override-update-guest");
        Long id = addGuest(f.finals().getId(), "甲", RosterConstants.PLACEMENT_HOLDING);

        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setGuestName("乙");
        bo.setGuestType(7L);
        bo.setGuestNumber("008");
        bo.setRemark("改过");
        rosterService.updateOverride(f.finals().getId(), id, bo);

        TStageRosterEntry e = entryOfGuest(f.finals().getId(), "乙");
        assertNotNull(e, "改名后应按新名字查得到");
        assertEquals(7L, e.getGuestType());
        assertEquals("008", e.getGuestNumber());
        assertEquals("改过", e.getRemark());
        assertNull(entryOfGuest(f.finals().getId(), "甲"), "旧名字不应残留");
    }

    /** 给外卡指定有效种子位(同一座位,避免造出重复占位)。 */
    @Test
    void updateOverrideSeatsGuestWithinPlan() {
        Fixture f = settledFinals("override-update-seed");
        TStageRosterOverrideBo add = new TStageRosterOverrideBo();
        add.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        add.setGuestName("坐席外卡");
        add.setSeedRank(1L);
        add.setPlacement("REPLACE");
        rosterService.addOverride(f.finals().getId(), add);
        TStageRosterEntry before = entryOfGuest(f.finals().getId(), "坐席外卡");
        assertEquals(1L, before.getSlot(), "应坐进 1 号位");

        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setSeedRank(before.getSlot());
        rosterService.updateOverride(f.finals().getId(), before.getId(), bo);

        assertEquals(1L, entryOfGuest(f.finals().getId(), "坐席外卡").getSlot());
    }

    @Test
    void updateOverrideRejectsMissingOverrideAndBlankGuestAndBadSeed() {
        Fixture f = settledFinals("override-update-errors");
        Long id = addGuest(f.finals().getId(), "丙", RosterConstants.PLACEMENT_HOLDING);

        assertThrows(ServiceException.class,
            () -> rosterService.updateOverride(f.finals().getId(), 999999L, new TStageRosterOverrideBo()),
            "覆盖不存在应报错");
        assertThrows(ServiceException.class,
            () -> rosterService.updateOverride(f.finals().getId(), id, null),
            "缺内容应报错");

        // "按姓名新建选手"路径会给外卡回填 playerId,所以这里用"关联了一个不存在的选手"来触发外卡档案校验
        TStageRosterOverrideBo badPlayer = new TStageRosterOverrideBo();
        badPlayer.setPlayerId(999999L);
        assertThrows(ServiceException.class,
            () -> rosterService.updateOverride(f.finals().getId(), id, badPlayer),
            "外卡关联不存在的选手应报错");

        TStageRosterOverrideBo badSeed = new TStageRosterOverrideBo();
        badSeed.setSeedRank(99L);
        assertThrows(ServiceException.class,
            () -> rosterService.updateOverride(f.finals().getId(), id, badSeed),
            "种子位超出计划规模应报错");
    }

    // ===================== deleteOverride(s) =====================

    /** 撤销"手工加人":座位还原成空位实体,人消失。 */
    @Test
    void deleteOverrideUndoesManualAdd() {
        Fixture f = settledFinals("override-delete-add");
        Long id = addGuest(f.finals().getId(), "待撤销外卡", "REPLACE");
        TStageRosterEntry added = entryOfGuest(f.finals().getId(), "待撤销外卡");
        long seat = added.getSlot();

        rosterService.deleteOverride(f.finals().getId(), id);

        TStageRosterEntry row = rosterService.entriesOf(f.finals().getId()).stream()
            .filter(e -> Objects.equals(e.getId(), id)).findFirst().orElse(null);
        assertNotNull(row, "座位的空位实体行要保留");
        assertEquals(seat, row.getSlot());
        assertEquals(StageConstants.SLOT_BYE, row.getSlotKind(), "撤销后应还原成空位");
        assertEquals(RosterConstants.ENTRY_ORIGIN_RULE, row.getOrigin());
        assertNull(row.getGuestName(), "外卡信息要清掉");
        // 不存在的 id:静默返回,不抛异常
        rosterService.deleteOverride(f.finals().getId(), 999999L);
    }

    /** 撤销"移出":把留了标记的规则行放回成 PLAYER。 */
    @Test
    void deleteOverrideUndoesRemoveMarker() {
        Fixture f = settledFinals("override-delete-remove");
        TStageRosterEntry advRow = entryOfSource(f.finals().getId(), f.adv1());
        assertNotNull(advRow);

        removeBySource(f.finals().getId(), f.adv1(), false);
        TStageRosterEntry marked = entryOfSource(f.finals().getId(), f.adv1());
        assertEquals(StageConstants.SLOT_BYE, marked.getSlotKind(), "移出应留下可撤销标记");

        rosterService.deleteOverride(f.finals().getId(), marked.getId());

        TStageRosterEntry restored = entryOfSource(f.finals().getId(), f.adv1());
        assertEquals(StageConstants.SLOT_PLAYER, restored.getSlotKind(), "撤销移出应放回名单");
        assertNotNull(restored.getEntryTag(), "入场性质要一并还原");
    }

    /** 撤销一个普通规则行(既不是移出标记、也不是人工行)→ 明确报错。 */
    @Test
    void deleteOverrideRejectsPlainRuleRow() {
        Fixture f = settledFinals("override-delete-reject");
        TStageRosterEntry ruleRow = entryOfSource(f.finals().getId(), f.adv2());
        assertNotNull(ruleRow);
        assertThrows(ServiceException.class,
            () -> rosterService.deleteOverride(f.finals().getId(), ruleRow.getId()),
            "规则行不是人工调整,不能撤销");
    }

    /** 批量撤销:一次撤掉两张外卡;空集合/空值直接返回。 */
    @Test
    void deleteOverridesBatchUndoesGuestsAndIgnoresEmpty() {
        Fixture f = settledFinals("override-delete-batch");
        Long g1 = addGuest(f.finals().getId(), "批量甲", RosterConstants.PLACEMENT_HOLDING);
        Long g2 = addGuest(f.finals().getId(), "批量乙", RosterConstants.PLACEMENT_HOLDING);

        rosterService.deleteOverrides(f.finals().getId(), List.of(g1, g2));
        assertNull(entryOfGuest(f.finals().getId(), "批量甲"), "批量撤销后外卡甲应清空");
        assertNull(entryOfGuest(f.finals().getId(), "批量乙"), "批量撤销后外卡乙应清空");
        rosterService.deleteOverrides(f.finals().getId(), null);
        rosterService.deleteOverrides(f.finals().getId(), List.of());
    }

    // ===================== removeRows =====================

    /** 移出并"后面的人顶上一位":被移出的座位消失,后面的人前移。 */
    @Test
    void removeRowsWithFillGapShiftsFollowing() {
        Fixture f = settledFinals("remove-fill-gap");
        TStageRosterEntry r1 = entryOfSource(f.finals().getId(), f.adv1());
        TStageRosterEntry r2 = entryOfSource(f.finals().getId(), f.adv2());
        TStageRosterEntry first = r1.getSlot() <= r2.getSlot() ? r1 : r2;
        TStageRosterEntry second = first == r1 ? r2 : r1;
        Long firstSource = first.getSourceCompetitorId();
        Long secondSource = second.getSourceCompetitorId();
        long firstSlot = first.getSlot();

        removeBySource(f.finals().getId(), firstSource, true);

        TStageRosterEntry shifted = entryOfSource(f.finals().getId(), secondSource);
        assertEquals(firstSlot, shifted.getSlot(), "后面的人应顶上被移出的座位");
        assertNull(entryOfSource(f.finals().getId(), firstSource), "顶上一位时不留移出标记");
    }

    /** 移出但不压缩:规则行留一个带来源引用的空位(可撤销)。 */
    @Test
    void removeRowsWithoutFillLeavesMarker() {
        Fixture f = settledFinals("remove-keep-gap");
        TStageRosterEntry adv = entryOfSource(f.finals().getId(), f.adv1());
        long seat = adv.getSlot();

        removeBySource(f.finals().getId(), f.adv1(), false);

        TStageRosterEntry marked = entryOfSource(f.finals().getId(), f.adv1());
        assertEquals(seat, marked.getSlot(), "不压缩时座位号保留");
        assertEquals(StageConstants.SLOT_BYE, marked.getSlotKind());
        assertNull(marked.getEntryTag(), "移出标记要清掉入场性质");
    }

    /** 移出人工行直接删行;空/无匹配的移出请求是空操作。 */
    @Test
    void removeRowsDeletesManualRowAndIgnoresNoMatch() {
        Fixture f = settledFinals("remove-manual");
        Long guestId = addGuest(f.finals().getId(), "要删的外卡", RosterConstants.PLACEMENT_HOLDING);

        TStageRosterRemoveBo del = new TStageRosterRemoveBo();
        del.setIds(List.of(guestId));
        rosterService.removeRosterRows(f.finals().getId(), del);
        assertNull(entryOfGuest(f.finals().getId(), "要删的外卡"), "人工行应被整行删除");

        rosterService.removeRosterRows(f.finals().getId(), new TStageRosterRemoveBo());
        TStageRosterRemoveBo noMatch = new TStageRosterRemoveBo();
        noMatch.setIds(List.of(999999L));
        rosterService.removeRosterRows(f.finals().getId(), noMatch);
    }

    // ===================== addOverride 校验 =====================

    @Test
    void addGuestRejectsDuplicateName() {
        Fixture f = settledFinals("override-duplicate");
        addGuest(f.finals().getId(), "同名外卡", RosterConstants.PLACEMENT_HOLDING);
        TStageRosterOverrideBo dup = new TStageRosterOverrideBo();
        dup.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        dup.setGuestName("同名外卡");
        dup.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        ServiceException e = assertThrows(ServiceException.class,
            () -> rosterService.addOverride(f.finals().getId(), dup));
        assertTrue(e.getMessage().contains("已存在"), "同名外卡应被去重拦下:" + e.getMessage());
    }

    @Test
    void addOverrideRejectsUnsupportedAndIncompleteInput() {
        Fixture f = settledFinals("override-bad-input");

        TStageRosterOverrideBo bogus = new TStageRosterOverrideBo();
        bogus.setOp("BOGUS");
        assertThrows(ServiceException.class, () -> rosterService.addOverride(f.finals().getId(), bogus));

        TStageRosterOverrideBo noName = new TStageRosterOverrideBo();
        noName.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        assertThrows(ServiceException.class, () -> rosterService.addOverride(f.finals().getId(), noName),
            "外卡既没关联选手也没姓名应报错");

        TStageRosterOverrideBo noSource = new TStageRosterOverrideBo();
        noSource.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        assertThrows(ServiceException.class, () -> rosterService.addOverride(f.finals().getId(), noSource),
            "手工拉人缺来源参赛方应报错");
    }

    /** 不能从本赛段自身、别的赛事、已弃权的人手工拉人。 */
    @Test
    void addSourceRejectsSelfOtherTournamentAndWithdrawn() {
        Fixture f = settledFinals("override-source-guards");

        // 本赛段自身:给决赛塞一个参赛方
        insertCompetitors(f.finals().getTournamentId(), f.finals().getId(), 1);
        Long selfId = firstCompetitorOf(f.finals().getId());
        assertThrows(ServiceException.class,
            () -> rosterService.addOverride(f.finals().getId(), sourceHold(selfId)),
            "不能从本赛段自身拉人");

        // 别的赛事
        Long otherTid = newTournament("override-other-tournament");
        TStageVo otherStage = newStage(otherTid, "别赛事海选", 2L, 1L, null);
        insertCompetitors(otherTid, otherStage.getId(), 2);
        Long otherId = firstCompetitorOf(otherStage.getId());
        assertThrows(ServiceException.class,
            () -> rosterService.addOverride(f.finals().getId(), sourceHold(otherId)),
            "跨赛事拉人应报错");

        // 已弃权
        Long withdrawn = firstLoserOf(f.semi().getId());
        TCompetitor w = competitorMapper.selectById(withdrawn);
        w.setOutcomeStatus(OutcomeStatusEnum.WITHDRAWN.getCode());
        competitorMapper.updateById(w);
        ServiceException e = assertThrows(ServiceException.class,
            () -> rosterService.addOverride(f.finals().getId(), sourceHold(withdrawn)));
        assertTrue(e.getMessage().contains("弃权"), "弃权的人不能加入:" + e.getMessage());
    }

    /** 赛段不是 DRAFT(已开赛/已结束)时整单禁止人工调整。 */
    @Test
    void rejectsEditWhenStageNotDraft() {
        Fixture f = settledFinals("override-not-draft");
        TStage upd = new TStage();
        upd.setId(f.finals().getId());
        upd.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(upd);

        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        bo.setGuestName("不该加上");
        bo.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        assertThrows(ServiceException.class, () -> rosterService.addOverride(f.finals().getId(), bo),
            "非 DRAFT 赛段不能人工调整");
    }

    // ===================== 工具 =====================

    private void removeBySource(Long stageId, Long sourceCompetitorId, boolean fillGap) {
        TStageRosterRemoveBo bo = new TStageRosterRemoveBo();
        bo.setSourceCompetitorIds(List.of(sourceCompetitorId));
        bo.setFillGap(fillGap);
        rosterService.removeRosterRows(stageId, bo);
    }

    private TStageRosterOverrideBo sourceHold(Long sourceCompetitorId) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        bo.setSourceCompetitorId(sourceCompetitorId);
        bo.setPlacement(RosterConstants.PLACEMENT_HOLDING);
        return bo;
    }

    private Long firstCompetitorOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId).orderByAsc(TCompetitor::getId))
            .get(0).getId();
    }

    private Long firstLoserOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId).orderByAsc(TCompetitor::getId)).stream()
            .filter(c -> !OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .findFirst().orElseThrow().getId();
    }

    /** 判该场为"左槽胜",返回胜者(左槽选手)ID;场次需已开始 */
    private Long judgeLeftWin(TMatch match) {
        List<TMatchParticipant> rows = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        assertEquals(2, rows.size(), match.getName() + " 应有两个座位");
        Long left = rows.get(0).getCompetitorId();
        Long right = rows.get(1).getCompetitorId();
        if (StageConstants.MATCH_PENDING.equals(matchMapper.selectById(match.getId()).getStatus())) {
            matchResultService.startMatch(match.getId());
        }
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(left, "WIN");
        outcomes.put(right, "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
        return left;
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
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
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + start
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
