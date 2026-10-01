package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.vo.TStageVo;
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

    // ===== 工具 =====

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
