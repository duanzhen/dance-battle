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
import com.dance.street.game.domain.vo.RosterPreviewVo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预晋级实时可见:上一轮淘汰赛<b>判完一场</b>,胜者就写进下一赛段中间态对应的座位,
 * 而不是等整个赛段结算才出名单。
 *
 * <p>回归的事故:中间层统一化后,中间层只在来源赛段 SETTLED 时才物化;而淘汰赛是每场判完
 * 就写 ADVANCE + 名次,于是"谁晋级了"要等「完成赛段」才看得到,现场失去实时性。</p>
 *
 * <p>另一半口径同时守住:实时显示 ≠ 可以确认。来源没全部结算时行是 PENDING、状态仍是
 * 「等来源结算」,「确认名单 / 开赛」依然被拦;整表重建只发生在赛段结算这类权威时刻。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class PreAdvanceRealtimeTest {

    private static final String DB_PATH = "target/pre-advance-realtime.db";

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

    @Test
    void winnerIsWrittenIntoNextStageMiddleLayerAsSoonAsHisMatchSettles() {
        Long tid = newTournament("pre-advance-realtime");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());

        initializeAndGenerate(semi.getId());
        List<TMatch> matches = matchesOf(semi.getId());
        assertEquals(2, matches.size(), "4 人淘汰赛首轮应为 2 场");
        assertEquals(0, playerRows(finals.getId()), "一场都没判时,下一赛段中间层不该有人");

        // 第 1 场判完:胜者立刻按名次坐进决赛的中间层
        Long firstWinner = judgeLeftWin(matches.get(0));
        assertEquals(1, playerRows(finals.getId()), "判完一场就该看到胜者,不等整个赛段结算");
        assertFalse(StageConstants.STAGE_SETTLED.equals(statusOf(semi.getId())),
            "此时来源赛段还在进行中");

        TCompetitor first = competitorMapper.selectById(firstWinner);
        TStageRosterEntry row = playedRows(finals.getId()).get(0);
        assertEquals(firstWinner, row.getSourceCompetitorId());
        assertEquals(first.getFinalRank(), row.getSlot(), "实时落座按结算名次(场次座位),不压紧");
        assertEquals(StageConstants.SLOT_PLAYER, row.getSlotKind());
        assertEquals(RosterConstants.ENTRY_STATUS_PENDING, row.getStatus(),
            "来源未全部结算:行是 PENDING(能看,还不能确认)");
        // 中间态口径:来源还没打完,其余空座位是"待定"(与大屏预排同一口径,不能一半轮空一半待定)
        assertTrue(rosterService.entriesOf(finals.getId()).stream()
                .filter(e -> !StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
                .allMatch(e -> StageConstants.SLOT_PENDING.equals(e.getSlotKind())),
            "来源没打完:空座位应是待定");

        // 实时显示 ≠ 可以确认
        RosterPreviewVo preview = rosterService.previewAssembled(finals.getId());
        assertEquals(Boolean.FALSE, preview.getReady(), "来源未结算完,状态仍是等待来源");
        assertThrows(ServiceException.class, () -> rosterService.applyRoster(finals.getId(), null));

        // 第 2 场判完:两个人都在,座位分别对应各自名次
        Long secondWinner = judgeLeftWin(matches.get(1));
        assertEquals(2, playerRows(finals.getId()), "每场判完各加一个人");
        assertTrue(playedRows(finals.getId()).stream()
                .map(TStageRosterEntry::getSourceCompetitorId)
                .toList()
                .containsAll(List.of(firstWinner, secondWinner)));

        // 完成赛段:整表按最终结果重建,行转 READY,名单可以确认
        lifecycleService.completeStage(semi.getId());
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(semi.getId()));
        assertTrue(rosterService.entriesOf(finals.getId()).stream()
                .allMatch(e -> RosterConstants.ENTRY_STATUS_READY.equals(e.getStatus())),
            "来源结算后行状态应转为 READY");
        assertTrue(rosterService.entriesOf(finals.getId()).stream()
                .noneMatch(e -> StageConstants.SLOT_PENDING.equals(e.getSlotKind())),
            "来源结算后不该再有待定座位:空位一律轮空");
        assertEquals(Boolean.TRUE, rosterService.previewAssembled(finals.getId()).getReady());
        assertEquals(2, rosterService.applyRoster(finals.getId(), null));
    }

    /** 判错重判:重置这一场后,参赛方回退待定,之前落进下游中间态的那个座位要还原成空位。 */
    @Test
    void resettingSourceClearsPreAdvancedRows() {
        Long tid = newTournament("pre-advance-reset");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());
        initializeAndGenerate(semi.getId());

        List<TMatch> matches = matchesOf(semi.getId());
        TMatch first = matches.get(0);
        TMatch second = matches.get(1);
        judgeLeftWin(first);
        assertEquals(1, playerRows(finals.getId()));
        Long otherWinnerRowId = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> e.getSlot() != null && e.getSlot() == 2L)
            .map(TStageRosterEntry::getId).findFirst().orElseThrow();

        // 判第 2 场后再重置第 1 场:只有第 1 场对应那一行回退
        judgeLeftWin(second);
        assertEquals(2, playerRows(finals.getId()));
        matchResultService.resetMatch(first.getId());
        assertEquals(1, playerRows(finals.getId()), "重判只影响这一场的那一行");
        assertEquals(2, rosterService.entriesOf(finals.getId()).size(),
            "座位还是两个(空位占号),只是那个人回到待定");
        TStageRosterEntry firstSeat = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> e.getSlot() != null && e.getSlot() == 1L).findFirst().orElseThrow();
        assertEquals(StageConstants.SLOT_PENDING, firstSeat.getSlotKind(),
            "被重判的那一行还原成待定(来源赛段还没打完)");
        TStageRosterEntry otherSeat = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> e.getSlot() != null && e.getSlot() == 2L).findFirst().orElseThrow();
        assertEquals(otherWinnerRowId, otherSeat.getId(), "另一场那一行没被重建(行 ID 不变)");
        assertEquals(StageConstants.SLOT_PLAYER, otherSeat.getSlotKind());
    }

    /**
     * 改判换人:重判后由<b>另一个</b>选手晋级,同一个来源座号(位置)上的人必须换成新的晋级者,
     * 不能留空、也不能把原来的晋级者留在下一赛段名单里。
     */
    @Test
    void rejudgeReplacesTheAdvancerAtTheSameSeat() {
        Long tid = newTournament("pre-advance-rejudge");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());
        initializeAndGenerate(semi.getId());

        TMatch first = matchesOf(semi.getId()).get(0);
        Long leftWinner = judgeWin(first, 0);
        assertEquals(leftWinner, holderAtSlot(finals.getId(), 1L), "左槽胜者先坐 1 号位");
        assertEquals(leftWinner, competitorMapper.selectById(leftWinner).getId());

        // 判错重判:重置这一场,改判为右槽胜
        matchResultService.resetMatch(first.getId());
        Long rightWinner = judgeWin(first, 1);

        assertEquals(rightWinner, holderAtSlot(finals.getId(), 1L),
            "改判后同一个座号应换成新的晋级者");
        assertTrue(rosterService.entriesOf(finals.getId()).stream()
                .noneMatch(e -> leftWinner.equals(e.getSourceCompetitorId())),
            "被改判掉的原晋级者不能留在下一赛段名单里");
        assertEquals(1, playerRows(finals.getId()), "改判不能留下重复的人");
        assertEquals(2, rosterService.entriesOf(finals.getId()).size(),
            "座位数不变(空位照占号)");
        assertEquals(rightWinner, competitorMapper.selectById(rightWinner).getId());
    }

    /**
     * 中间态按来源边放行:来源还没结算时,来自该来源的行与新加人都不能动;
     * 结算后立刻放开(不必等别的来源)。
     */
    @Test
    void middleStateLocksOnlyRowsFromUnsettledSources() {
        Long tid = newTournament("pre-advance-readonly");
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 4);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());
        initializeAndGenerate(semi.getId());
        List<TMatch> matches = matchesOf(semi.getId());
        Long advanced = judgeLeftWin(matches.get(0));
        assertEquals(1, playerRows(finals.getId()), "实时能看到已晋级的人");

        // 新增行(加外卡)仍要等来源全部结算:这条新行的座位还没定案
        TStageRosterOverrideBo guest = new TStageRosterOverrideBo();
        guest.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        guest.setGuestName("临时外卡");
        assertThrows(ServiceException.class, () -> rosterService.addOverride(finals.getId(), guest),
            "来源还没结算,不能往中间态加人");

        // 来自未结算来源的选手:按行锁住,移出会被拦下
        TStageRosterOverrideBo removeAdvancer = new TStageRosterOverrideBo();
        removeAdvancer.setOp(RosterConstants.OVERRIDE_REMOVE);
        removeAdvancer.setSourceCompetitorId(advanced);
        ServiceException blocked = assertThrows(ServiceException.class,
            () -> rosterService.addOverride(finals.getId(), removeAdvancer),
            "来自还没结束的赛段的人不能调整");
        assertTrue(blocked.getMessage().contains("还没结束"), "报错应说明来源未结束:" + blocked.getMessage());

        // 来源结算后放开
        judgeLeftWin(matches.get(1));
        lifecycleService.completeStage(semi.getId());
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(semi.getId()));
        rosterService.addOverride(finals.getId(), guest);
        assertEquals(1, rosterService.listOverrides(finals.getId()).size(), "结算后可以正常加人");
        rosterService.addOverride(finals.getId(), removeAdvancer);
        TStageRosterEntry removed = rosterService.entriesOf(finals.getId()).stream()
            .filter(e -> Objects.equals(e.getSourceCompetitorId(), advanced))
            .findFirst().orElse(null);
        assertEquals(StageConstants.SLOT_BYE, removed == null ? null : removed.getSlotKind(),
            "结算后也能把该来源的人移出(座位还原成空位)");
    }

    /**
     * 轮空场次判胜(=点开始即自动结算)也要实时写进下一赛段中间态。
     *
     * <p>回归事故:只有"裁判判完一场"那条路会同步,轮空判胜那条路没同步 ——
     * 现场就是"点了开始,场次结束了,人却没出现在下一段"。</p>
     */
    @Test
    void byeWinIsAlsoWrittenIntoNextStageImmediately() {
        Long tid = newTournament("pre-advance-bye");
        // 4 签表只坐 3 人:座位 4 空 → 第 1 场是"选手 vs 轮空"
        TStageVo semi = newStage(tid, "半决赛", 4L, 2L, null);
        insertCompetitors(tid, semi.getId(), 3);
        TStageVo finals = newStage(tid, "决赛", 2L, 1L, semi.getId());

        initializeAndGenerate(semi.getId());
        TMatch byeMatch = matchesOf(semi.getId()).stream()
            .filter(m -> !participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .isNotNull(TMatchParticipant::getCompetitorId)).isEmpty())
            .filter(m -> participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .isNotNull(TMatchParticipant::getCompetitorId)).size() == 1)
            .findFirst().orElseThrow(() -> new AssertionError("应有一场为单人 vs 轮空"));
        Long winnerId = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, byeMatch.getId())
                .isNotNull(TMatchParticipant::getCompetitorId))
            .get(0).getCompetitorId();

        // 导播台点「开始」:轮空场立刻结算
        matchResultService.startMatch(byeMatch.getId());
        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(byeMatch.getId()).getStatus(),
            "轮空场点开始即结算");
        assertEquals(OutcomeStatusEnum.ADVANCE.getCode(),
            competitorMapper.selectById(winnerId).getOutcomeStatus(), "轮空判胜应标记晋级");
        assertEquals(List.of(winnerId), rosterService.entriesOf(finals.getId()).stream()
                .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
                .map(TStageRosterEntry::getSourceCompetitorId).toList(),
            "轮空晋级的人要立刻出现在下一赛段中间态(不能只结算不落位)");
    }

    // ===== 工具 =====

    /** 判该场为"左槽胜",返回胜者(左槽选手)ID */
    private Long judgeLeftWin(TMatch match) {
        return judgeWin(match, 0);
    }

    /** 判该场指定槽位获胜,返回胜者 ID(重置后场次已是 GAMING,不再重复 start) */
    private Long judgeWin(TMatch match, int winnerIndex) {
        List<Long> ids = participantIds(match);
        assertEquals(2, ids.size(), match.getName() + " 应有两个座位");
        if (StageConstants.MATCH_PENDING.equals(matchMapper.selectById(match.getId()).getStatus())) {
            matchResultService.startMatch(match.getId());
        }
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(match.getId());
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(ids.get(winnerIndex), "WIN");
        outcomes.put(ids.get(1 - winnerIndex), "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
        return ids.get(winnerIndex);
    }

    private List<Long> participantIds(TMatch match) {
        List<TMatchParticipant> rows = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        return rows.stream().map(TMatchParticipant::getCompetitorId).toList();
    }

    /** 指定座号上现在是谁(没人返回 null) */
    private Long holderAtSlot(Long stageId, long slot) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> e.getSlot() != null && e.getSlot() == slot)
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .findFirst().orElse(null);
    }

    private List<TStageRosterEntry> playedRows(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .toList();
    }

    private long playerRows(Long stageId) {
        return playedRows(stageId).size();
    }

    private String statusOf(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return stage == null ? null : stage.getStatus();
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId).orderByAsc(TMatch::getDisplayRow));
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
