package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterOverrideMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.domain.TStageRosterOverride;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITMatchResultService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮空实体化 + 座位密排的回归测试。
 *
 * <p>现场问题:24 人进 32 签表,但名单座位号是 5..28(带空洞的整体偏移),于是
 * 空的 1-4 与 29-32 被种子摆位配成"双方都轮空"的空场次,中间态画出来的位置也和实际生成对不上。</p>
 *
 * <p>约定:淘汰赛座位必须密排(第 1..n 号),轮空/待定也要落 participant 行(座位实体化),
 * competitor_id 为空 + slot_kind 区分 BYE/PENDING。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class KnockoutByeSlotTest {

    private static final String DB_PATH = "target/knockout-bye-slot.db";

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
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TStageRosterOverrideMapper rosterOverrideMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITMatchResultService matchResultService;

    /** 每个座位都落行:1 人 + 1 轮空 = 2 行,轮空行 competitor_id 为空且 slot_kind=BYE。 */
    @Test
    void byeSeatsAreMaterializedInsteadOfSkipped() {
        TStageVo stage = newKnockout("24people32");
        insertCompetitors(stage.getId(), 24, 1);
        initializeAndGenerate(stage.getId());

        List<TMatchParticipant> all = participantRows(stage.getId());
        assertEquals(32, all.size(), "32 签表的每个座位都应有一行");
        long byes = all.stream().filter(p -> StageConstants.SLOT_BYE.equals(p.getSlotKind())).count();
        assertEquals(8, byes, "24 人进 32 签表应有 8 个轮空座位");
        for (TMatchParticipant p : all) {
            if (StageConstants.SLOT_BYE.equals(p.getSlotKind())) {
                assertNull(p.getCompetitorId(), "轮空座位不应绑定参赛方");
            } else {
                assertTrue(p.getCompetitorId() != null, "PLAYER 座位必须绑定参赛方");
            }
        }
        for (TMatch m : matchesOf(stage.getId())) {
            assertEquals(2, rowsOfMatch(m.getId()).size(), m.getName() + " 应恰好两个座位");
        }
    }

    /** 座位是"位置":中间态排好的座位必须原样生成,不能被压成 1..n 重排。 */
    @Test
    void authoredSeatsAreKeptByGeneration() {
        TStageVo stage = newKnockout("sparse");
        insertCompetitors(stage.getId(), 24, 5); // seed_rank 5..28
        initializeAndGenerate(stage.getId());

        List<Long> seats = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId()).orderByAsc(TCompetitor::getSeedRank))
            .stream().map(TCompetitor::getSeedRank).toList();
        assertEquals(24, seats.size());
        assertEquals(5L, seats.get(0), "初始化不得把排好的座位压回 1..n");
        assertEquals(28L, seats.get(23));

        // 座位 1..4 / 29..32 是空的 → 对应轮空;1 人 + 1 轮空的场次必须准确落在座位两侧
        long byes = participantRows(stage.getId()).stream()
            .filter(p -> StageConstants.SLOT_BYE.equals(p.getSlotKind())).count();
        assertEquals(8, byes, "空座位数应等于轮空数");
    }

    /**
     * 双方都轮空的场次不产生晋级者,但它的「座位名次」要留给下一赛段 —— 即轮空也晋级到下一 stage。
     * 下一赛段按来源名次坐位,空洞原样留空(就是轮空),签表结构不塌。
     */
    @Test
    void doubleByeKeepsItsSeatInNextStage() {
        Long tid = newTournament("bye-advance");
        TStageVo r32 = newStage(tid, "32强", 32L, 16L, null);
        insertCompetitors(r32.getId(), 24, 5); // 座位 5..28 → 4 场双方轮空
        initializeAndGenerate(r32.getId());

        // 逐场结算:两人正常判胜负,双方轮空的开始即自动结算且不产生晋级者
        java.util.Set<Long> byeRanks = new java.util.HashSet<>();
        for (TMatch m : matchesOf(r32.getId())) {
            List<TMatchParticipant> real = rowsOfMatch(m.getId()).stream()
                .filter(p -> p.getCompetitorId() != null).toList();
            matchResultService.startMatch(m.getId());
            if (real.size() < 2) {
                byeRanks.add(m.getDisplayRow() + 1);
                continue;
            }
            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(m.getId());
            java.util.Map<Long, String> outcomes = new java.util.HashMap<>();
            outcomes.put(real.get(0).getCompetitorId(), "WIN");
            outcomes.put(real.get(1).getCompetitorId(), "LOSS");
            bo.setOutcomes(outcomes);
            matchResultService.submitResult(bo);
        }
        assertEquals(4, byeRanks.size(), "24 人进 32 签表应有 4 场双方轮空");
        lifecycleService.completeStage(r32.getId());

        List<TCompetitor> advancers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, r32.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(12, advancers.size(), "只有 12 个真人晋级(轮空场次不出人)");
        for (TCompetitor a : advancers) {
            assertTrue(!byeRanks.contains(a.getFinalRank()),
                "真人晋级者的名次不应落在轮空场次的座位上");
        }

        // 下一赛段按来源名次坐位:轮空座位保持空着
        TStageVo r16 = newStage(tid, "16强", 16L, 8L, r32.getId());
        rosterService.applyRoster(r16.getId(), null);
        List<TCompetitor> comps16 = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, r16.getId()));
        assertEquals(12, comps16.size());
        for (TCompetitor c : comps16) {
            TCompetitor src = competitorMapper.selectById(c.getSourceCompetitorId());
            assertEquals(src.getFinalRank(), c.getSeedRank(),
                "晋级者应坐回自己的名次座位,轮空名次对应的座位留空");
            assertTrue(!byeRanks.contains(c.getSeedRank()));
        }
        assertTrue(byeRanks.contains(1L), "本例轮空名次含 1(场1 双方轮空)");
    }

    /**
     * 核心契约:生成出来的每一场必须与中间态按座位画出来的完全一致
     * —— 座位空着就是轮空,双方都空的场次也照常生成(不出人不顶替)。
     *
     * <p>现场形状:32 签表、24 人坐在 5..28 → 1-32 / 3-30 / 29-4 / 31-2 四场两边都轮空,
     * 其余 12 场各有两人。这里逐场比对座位号 → 参赛方。</p>
     */
    @Test
    void generatedBracketMatchesMiddleStateSeatLayout() {
        TStageVo stage = newKnockout("seat-fidelity");
        insertCompetitors(stage.getId(), 24, 5); // 座位 5..28
        initializeAndGenerate(stage.getId());

        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()));
        java.util.Map<Long, Long> bySeat = new java.util.HashMap<>();
        comps.forEach(c -> bySeat.put(c.getSeedRank(), c.getId()));

        int[] layout = com.dance.street.game.engine.generator.KnockoutGenerator.seedLayout(32);
        List<TMatch> matches = matchesOf(stage.getId());
        assertEquals(16, matches.size(), "32 签表应为 16 场");

        int bothBye = 0;
        int bothPlayer = 0;
        for (TMatch m : matches) {
            int pairIndex = Math.toIntExact(m.getDisplayRow());
            Long expectLeft = bySeat.get((long) layout[2 * pairIndex]);
            Long expectRight = bySeat.get((long) layout[2 * pairIndex + 1]);
            List<TMatchParticipant> rows = rowsOfMatch(m.getId());
            assertEquals(2, rows.size(), m.getName() + " 应有两个座位行");
            TMatchParticipant slot0 = rows.stream()
                .filter(p -> Long.valueOf(0L).equals(p.getDisplaySlotIndex())).findFirst().orElseThrow();
            TMatchParticipant slot1 = rows.stream()
                .filter(p -> Long.valueOf(1L).equals(p.getDisplaySlotIndex())).findFirst().orElseThrow();

            assertEquals(expectLeft, slot0.getCompetitorId(),
                m.getName() + " 左槽应等于中间态座位 " + layout[2 * pairIndex] + " 的人(空=轮空)");
            assertEquals(expectRight, slot1.getCompetitorId(),
                m.getName() + " 右槽应等于中间态座位 " + layout[2 * pairIndex + 1] + " 的人(空=轮空)");
            assertEquals(expectLeft == null, StageConstants.SLOT_BYE.equals(slot0.getSlotKind()));
            assertEquals(expectRight == null, StageConstants.SLOT_BYE.equals(slot1.getSlotKind()));

            if (expectLeft == null && expectRight == null) {
                bothBye++;
            } else if (expectLeft != null && expectRight != null) {
                bothPlayer++;
            }
        }
        assertEquals(4, bothBye, "1-32 / 3-30 / 29-4 / 31-2 四场两边都轮空");
        assertEquals(12, bothPlayer, "其余 12 场各两人");
    }

    /**
     * 中间态"拖到轮空位"= 与该座位互换:选手落到目标座位,原座位空出来(变成轮空),
     * 其余人的座位一律不动(不出现整表重排)。
     */
    @Test
    void movingIntoByeSeatSwapsWithoutReflow() {
        TStageVo stage = newKnockout("swap");
        insertCompetitors(stage.getId(), 24, 1);
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()).orderByAsc(TCompetitor::getSeedRank));
        TCompetitor moved = comps.get(4); // 原座位 5
        assertEquals(5L, moved.getSeedRank());

        List<com.dance.street.game.domain.bo.TStageRosterOrderBo.Item> items = new java.util.ArrayList<>();
        for (TCompetitor c : comps) {
            com.dance.street.game.domain.bo.TStageRosterOrderBo.Item it =
                new com.dance.street.game.domain.bo.TStageRosterOrderBo.Item();
            it.setSourceCompetitorId(c.getId());
            it.setSeedRank(c.getId().equals(moved.getId()) ? 32L : c.getSeedRank());
            items.add(it);
        }
        rosterService.reorderRoster(stage.getId(), items);

        List<TStageRosterOverride> overrides = rosterOverrideMapper.selectList(
            Wrappers.<TStageRosterOverride>lambdaQuery()
                .eq(TStageRosterOverride::getTargetStageId, stage.getId())
                .eq(TStageRosterOverride::getOp, "SEED"));
        long untouched = overrides.stream()
            .filter(o -> !moved.getId().equals(o.getSourceCompetitorId()))
            .filter(o -> o.getSeedRank() != null && o.getSeedRank() >= 1 && o.getSeedRank() <= 24)
            .count();
        assertEquals(23, untouched, "其余 23 人的座位不得被重排");
        TStageRosterOverride movedOverride = overrides.stream()
            .filter(o -> moved.getId().equals(o.getSourceCompetitorId()))
            .findFirst().orElseThrow();
        assertEquals(32L, movedOverride.getSeedRank(), "被拖动的选手应落到轮空座位 32");
    }

    /** 名单预览如实报出库里的座位号(位置语义),不做压紧。 */
    @Test
    void rosterPreviewReportsAuthoredSeats() {
        TStageVo stage = newKnockout("preview");
        insertCompetitors(stage.getId(), 24, 5);
        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setRosterApplied(1L);
        stageMapper.updateById(upd);

        RosterPreviewVo preview = rosterService.previewAssembled(stage.getId());
        List<RosterPreviewItemVo> items = preview.getItems();
        assertEquals(24, items.size());
        assertEquals(5L, items.get(0).getSeedRank());
        assertEquals(28L, items.get(items.size() - 1).getSeedRank());
    }

    // ===== 工具 =====

    private TStageVo newKnockout(String name) {
        return newStage(newTournament(name), name, 32L, 16L, null);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, Long start, Long end, Long prevStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(prevStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + start
            + ",\"advanceCount\":" + end + ",\"pairingMode\":\"SEED\"}}");
        return stageService.insertByBo(bo);
    }

    private void insertCompetitors(Long stageId, int count, int seedStart) {
        for (int i = 0; i < count; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(stageMapper.selectById(stageId).getTournamentId());
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("P" + (i + 1));
            c.setNumber(String.valueOf(i + 1));
            c.setSeedRank((long) (i + seedStart));
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
        }
    }

    private void initializeAndGenerate(Long stageId) {
        InitializeStageBo init = new InitializeStageBo();
        init.setStageId(stageId);
        lifecycleService.initialize(init);
        GenerateMatchesBo gen = new GenerateMatchesBo();
        gen.setStageId(stageId);
        lifecycleService.generateMatches(gen);
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId).orderByAsc(TMatch::getDisplayRow));
    }

    private List<TMatchParticipant> rowsOfMatch(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId));
    }

    private List<TMatchParticipant> participantRows(Long stageId) {
        List<Long> matchIds = matchesOf(stageId).stream().map(TMatch::getId).toList();
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds));
    }
}
