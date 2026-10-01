package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对战树"上方区域全轮空"整链回归:32 签表只坐 5..28(顶部 1-4 / 底部 29-32 全空),
 * 顶部那几场两边都轮空 —— 这种签表必须能一路跑到决赛,不能卡在任何一段。
 *
 * <p>现场事故背景:轮空/待定混用后,空位会被当成"待定"卡住不结算,或者反过来被当轮空
 * 直接放人晋级。这里用真实链路(确认名单 → 开始赛段 → 逐场判罚(轮空场点开始即自动结算)
 * → 完成赛段 → 下一段)把 32→16→8→4→2 全部跑一遍,断言每一段都正常结算、最后只剩 1 名冠军。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class KnockoutTopByeChainE2ETest {

    private static final String DB_PATH = "target/knockout-top-bye-chain.db";

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
    void topByesRunThroughWholeKnockoutChain() {
        Long tid = newTournament("顶部轮空整链");
        // 32 签表:24 人只坐在 5..28 → 1-32 / 3-30 / 29-4 / 31-2 四场两边都轮空(顶部两场)
        TStageVo first = newStage(tid, "32强", 32L, 16L, null);
        insertCompetitors(tid, first.getId(), 24, 5);

        Long prevId = first.getId();
        int[] sizes = {16, 8, 4, 2};
        String[] names = {"16强", "8强", "半决赛", "决赛"};
        for (int i = 0; i < sizes.length; i++) {
            TStageVo next = newStage(tid, names[i], (long) sizes[i], (long) sizes[i] / 2, prevId);
            prevId = next.getId();
        }

        // 第 1 段:入口赛段不需要确认名单,直接开赛
        lifecycleService.startStage(first.getId());
        List<TMatch> firstMatches = matchesOf(first.getId());
        assertEquals(16, firstMatches.size(), "32 签表首轮 16 场");
        long doubleByes = firstMatches.stream().filter(m -> realParts(m.getId()).isEmpty()).count();
        assertEquals(4, doubleByes, "1-32 / 3-30 / 29-4 / 31-2 四场两边都轮空");
        judgeAndComplete(first.getId());
        assertEquals(12, countOutcome(first.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "24 人打 32 签表:12 场真人对决各出 1 名胜者,轮空场次不出人");

        // 顶部轮空留下的名次空洞,要原样带到下一段(座位是位置,不压紧)
        Long secondId = stageMapper.selectById(first.getId()).getNextStageId();
        assertNotNull(secondId);
        assertEquals(12, rosterService.applyRoster(secondId, null));
        List<TStageRosterEntry> secondRows = rosterService.entriesOf(secondId);
        assertEquals(16, secondRows.size(), "16 强仍是 16 个座位");
        // 谁坐在哪个座位 = 上一段那场比赛的名次(displayRow+1);轮空场次没人晋级,它们的名次座位保持空着
        java.util.Set<Long> expectSeats = firstMatches.stream()
            .filter(m -> !realParts(m.getId()).isEmpty())
            .map(m -> m.getDisplayRow() + 1)
            .collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> filledSeats = secondRows.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSlot)
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectSeats, filledSeats,
            "晋级者按结算名次坐回座位;4 场双轮空留下的名次座位必须是空位(不许压紧)");
        assertEquals(16 - 4, filledSeats.size(), "24 人 32 签表 → 12 人晋级");

        // 剩下各段:确认名单 → 开赛 → 判完(轮空场点开始即自动结算) → 完成
        Long stageId = secondId;
        int guard = 0;
        while (stageId != null && guard++ < 10) {
            TStage stage = stageMapper.selectById(stageId);
            assertNotNull(stage);
            if (!Long.valueOf(1L).equals(stage.getRosterApplied())) {
                rosterService.applyRoster(stageId, null);
            }
            judgeAndComplete(stageId);
            stageId = stageMapper.selectById(stageId).getNextStageId();
        }

        // 决赛打完:最后只剩 1 名冠军,全链每段都已结算
        List<TStage> all = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, tid));
        assertEquals(5, all.size(), "32→16→8→4→2 共 5 个赛段");
        assertTrue(all.stream().allMatch(s -> StageConstants.STAGE_SETTLED.equals(s.getStatus())),
            "每个赛段都应正常结算,不能卡住:" + all.stream()
                .map(s -> s.getName() + "=" + s.getStatus()).toList());
        assertEquals(1, countOutcome(prevId, OutcomeStatusEnum.ADVANCE.getCode()),
            "决赛应产生且只产生 1 名冠军");
    }

    // ===== 工具 =====

    /** 开赛 → 逐场处理(轮空场"开始"即自动结算,真人场提交胜负) → 完成赛段 */
    private void judgeAndComplete(Long stageId) {
        // 第 1 段在用例里已先开赛(要断言签表形状),这里只在还是草稿时补开赛
        if (StageConstants.STAGE_DRAFT.equals(stageMapper.selectById(stageId).getStatus())) {
            lifecycleService.startStage(stageId);
        }
        // 中间态口径:本段名单来源已结算,空座位只该是"轮空",不该再有"待定"
        List<Long> matchIds = matchesOf(stageId).stream().map(TMatch::getId).toList();
        assertTrue(participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .isNull(TMatchParticipant::getCompetitorId))
            .stream().noneMatch(p -> StageConstants.SLOT_PENDING.equals(p.getSlotKind())),
            "来源已结算的空座位必须是轮空,不能留待定");
        for (TMatch m : matchesOf(stageId)) {
            List<TMatchParticipant> real = realParts(m.getId());
            matchResultService.startMatch(m.getId());
            if (real.size() < 2) {
                assertEquals(StageConstants.MATCH_SETTLED, statusOfMatch(m.getId()),
                    m.getName() + " 是轮空场,点开始应立刻自动结算");
                continue;
            }
            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(m.getId());
            Map<Long, String> outcomes = new HashMap<>();
            outcomes.put(real.get(0).getCompetitorId(), "WIN");
            outcomes.put(real.get(1).getCompetitorId(), "LOSS");
            bo.setOutcomes(outcomes);
            matchResultService.submitResult(bo);
        }
        lifecycleService.completeStage(stageId);
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stageId).getStatus(),
            "赛段应正常结束");
        assertEquals(0, matchesOf(stageId).stream()
                .filter(m -> !StageConstants.MATCH_SETTLED.equals(m.getStatus())).count(),
            "结束后不该还有未结算场次");
        assertTrue(rosterService.entriesOf(stageId).isEmpty()
                || rosterService.entriesOf(stageId).stream().allMatch(e ->
                    RosterConstants.ENTRY_STATUS_READY.equals(e.getStatus())),
            "本段中间层(如有)来源已结算,行状态应为 READY");
    }

    private List<TMatchParticipant> realParts(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private String statusOfMatch(Long matchId) {
        TMatch m = matchMapper.selectById(matchId);
        return m == null ? null : m.getStatus();
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
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

    /** 从 seedStart 开始连续坐人(留出顶部/底部空座位) */
    private void insertCompetitors(Long tournamentId, Long stageId, int count, int seedStart) {
        for (int i = 0; i < count; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tournamentId);
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("P" + (i + 1));
            c.setNumber(String.valueOf(i + 1));
            c.setSeedRank((long) (i + seedStart));
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
        }
    }
}
