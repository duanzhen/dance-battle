package com.dance.street.game.stage;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITStageRosterService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 擂台赛段的对战树数据源:绑定「对战树」控件后,开赛前就要能看到"目前谁进来了"。
 *
 * <p>验证两件事:</p>
 * <ol>
 *   <li>名单还没写入(上一赛段已产生晋级者、尚未确认装配)时,预排接口要返回晋级者的座位名单——
 *       此前非淘汰赛目标一律 {@code UNSUPPORTED},擂台赛段的对战树只能显示空白;</li>
 *   <li>名单已写入时按 seedRank 返回座位,座位有空洞也不压紧(否则对战树位置与中间态不一致)。</li>
 * </ol>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ArenaPreBracketTest {

    private static final String DB_PATH = "target/arena-pre-bracket.db";

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
    private TCompetitorMapper competitorMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageRosterService rosterService;

    /** 上一赛段已出晋级者、擂台赛名单还没写入:对战树应能看到这 8 个人的座位。 */
    @Test
    void arenaPreBracketShowsAdvancersBeforeRosterConfirmed() {
        Long tid = newTournament("擂台预排");
        TStageVo prev = newStage(tid, "16强", "KNOCKOUT", 16L, 8L, null);
        prev = settleWithAdvancers(tid, prev.getId(), 16, 8);
        TStageVo arena = newStage(tid, "擂台赛", "ARENA", 8L, 1L, prev.getId());
        wireRosterFrom(arena.getId(), prev.getId());

        PreBracketVo vo = stageService.getPreBracket(arena.getId());

        assertEquals("PREVIEW", vo.getStatus(), "擂台赛段应支持预排,而不是 UNSUPPORTED");
        assertEquals(8, vo.getSeededCompetitors().size(), "应列出 8 名预排晋级者");
        List<Long> seats = vo.getSeededCompetitors().stream()
            .map(PreBracketVo.PreSeed::getSeedRank).toList();
        assertEquals(new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)), seats,
            "晋级者应坐回自己的座位");
        assertTrue(vo.getSeededCompetitors().stream().allMatch(s -> s.getName() != null && !s.getName().isBlank()),
            "预排名单应带姓名,对战树才能显示谁进去了");
        assertTrue(vo.getPairs() == null || vo.getPairs().isEmpty(),
            "擂台赛没有对阵树,不应生成预排配对");
    }

    /** 名单已写入:按 seedRank 返回座位,座位有空洞也不压紧(与中间态同一口径)。 */
    @Test
    void arenaRosterKeepsSeatWhenHoleExists() {
        Long tid = newTournament("擂台座位空洞");
        TStageVo prev = newStage(tid, "16强", "KNOCKOUT", 16L, 8L, null);
        prev = settleWithAdvancers(tid, prev.getId(), 16, 8);
        TStageVo arena = newStage(tid, "擂台赛", "ARENA", 8L, 1L, prev.getId());

        // 中间态移出第 3 位后:座位变成 1、2、4、5(第 3 位留空)
        for (long seat : new long[]{1L, 2L, 4L, 5L}) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(arena.getId());
            c.setType(0L);
            c.setName("P" + seat);
            c.setNumber(String.valueOf(seat));
            c.setSeedRank(seat);
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            c.setSourceCompetitorId(prev.getId() + seat);
            competitorMapper.insert(c);
        }

        PreBracketVo vo = stageService.getPreBracket(arena.getId());

        assertEquals("GENERATED", vo.getStatus());
        assertEquals(new ArrayList<>(List.of(1L, 2L, 4L, 5L)),
            vo.getSeededCompetitors().stream().map(PreBracketVo.PreSeed::getSeedRank).toList(),
            "座位有空洞时不能压紧成 1..n");
    }

    /** 其他赛制(小组赛)目标仍不做预排,避免这次放开被误用到别处。 */
    @Test
    void otherStageModesStillUnsupported() {
        Long tid = newTournament("预排白名单");
        TStageVo prev = newStage(tid, "16强", "KNOCKOUT", 16L, 8L, null);
        prev = settleWithAdvancers(tid, prev.getId(), 16, 8);
        TStageVo group = newStage(tid, "小组赛", "GROUP", 8L, 4L, prev.getId());

        assertEquals("UNSUPPORTED", stageService.getPreBracket(group.getId()).getStatus());
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, String mode, Long start, Long end, Long prevStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(prevStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"" + mode + "\",\"scale\":" + start + ",\"format\":\"BO1\"}");
        return stageService.insertByBo(bo);
    }

    /** 造一个已结算的上一赛段:前 advanceCount 人晋级并带 finalRank(座位)。 */
    private TStageVo settleWithAdvancers(Long tid, Long stageId, int total, int advance) {
        for (int i = 1; i <= total; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("S" + i);
            c.setNumber(String.valueOf(i));
            c.setSeedRank((long) i);
            boolean advanced = i <= advance;
            c.setOutcomeStatus(advanced ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode());
            c.setFinalRank(advanced ? (long) i : null);
            competitorMapper.insert(c);
        }
        TStage patch = new TStage();
        patch.setId(stageId);
        patch.setStatus(StageConstants.STAGE_SETTLED);
        patch.setIsInitialized(1L);
        stageMapper.updateById(patch);
        return stageService.queryById(stageId);
    }

    /** 给目标赛段写默认来源组:取上一赛段的晋级者(与模板生成的一致)。 */
    private void wireRosterFrom(Long targetStageId, Long sourceStageId) {
        // 来源组现在存在独立边表里:按服务口径写入(不再直接拼 JSON)
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        bo.setFillMode("AUTO");
        rosterService.addGroups(targetStageId, bo);
    }
}
