package com.dance.street.game.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.domain.TTournament;
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

/**
 * 自定义出口配置与中间层名单的一致性:改出口规则后,中间层必须立刻跟着变。
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class ExitConfigConsistencyTest {

    private static final String DB_PATH = "target/exit-config-consistency.db";

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
    @Autowired private TStageMapper stageMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;

    /** 出口面板的操作序列:追加按名次段的自定义出口 → 摘掉"整单晋级"默认组 → 中间层立刻收敛。 */
    @Test
    void customExitRebuildsMiddleLayerImmediately() {
        Long tid = newTournament("exit-config");
        TStageVo audition = newStage(tid, "海选", "AUDITION", 0L, 2L, null);
        insertCompetitors(tid, audition.getId(), 4);
        settle(audition.getId());
        TStageVo round16 = newStage(tid, "16强", "KNOCKOUT", 4L, 2L, audition.getId());
        assertEquals(4, playerRows(round16.getId()));

        rosterService.addGroups(round16.getId(), customExit(audition.getId(), 1, 2));
        removeGeneratedDefault(round16.getId(), audition.getId());

        assertEquals(2, playerRows(round16.getId()), "自定义出口(第 1~2 名)应立刻把中间层收敛到 2 人");
        assertEquals(4, rosterService.entriesOf(round16.getId()).size(),
            "座位数仍按计划规模,空位照占号");
    }

    /** 改链顺序后,出口规则变了 —— 中间层必须跟着变(不允许留着按旧规则算出来的行)。 */
    @Test
    void linkChangeRebuildsMiddleLayerAfterExitRulesChanged() {
        Long tid = newTournament("exit-config-move");
        TStageVo audition = newStage(tid, "海选", "AUDITION", 0L, 2L, null);
        insertCompetitors(tid, audition.getId(), 4);
        settle(audition.getId());
        TStageVo round16 = newStage(tid, "16强", "KNOCKOUT", 4L, 2L, audition.getId());
        insertCompetitors(tid, round16.getId(), 4);
        settle(round16.getId());
        TStageVo revival = newStage(tid, "复活赛", "KNOCKOUT", 8L, 2L, round16.getId());

        // 复活赛:默认整单接 16强(4 人) + 自定义出口"海选第 1~2 名"(2 人)= 6 人
        rosterService.addGroups(revival.getId(), customExit(audition.getId(), 1, 2));
        assertEquals(6, playerRows(revival.getId()));

        // 把 16强 移到复活赛之后:复活赛的直接前驱变成海选,"16强整单晋级"默认组被摘掉
        stageService.moveStageAfter(round16.getId(), revival.getId());

        assertEquals(2, playerRows(revival.getId()),
            "改链后复活赛只剩「海选第 1~2 名」这一条出口,中间层必须同步重建");
    }

    // ===== 工具 =====

    private TStageRosterBo customExit(Long sourceStageId, int rankStart, int rankEnd) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode("AUTO");
        g.setQuota(0);
        g.setRankStart(rankStart);
        g.setRankEnd(rankEnd);
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setGroups(List.of(g));
        return bo;
    }

    /** 模拟出口面板"按名次段出口已覆盖第 1 名 → 摘掉整单晋级默认组" */
    private void removeGeneratedDefault(Long targetStageId, Long sourceStageId) {
        List<TStageRosterVo> rosters = rosterService.listByTarget(targetStageId);
        List<TStageRosterGroupBo> groups = rosters.get(0).getGroups();
        int idx = -1;
        for (int i = 0; i < groups.size(); i++) {
            TStageRosterGroupBo g = groups.get(i);
            if (java.util.Objects.equals(g.getSourceStageId(), sourceStageId)
                && g.getZone() == null && g.getRankStart() == null && g.getRankEnd() == null) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            rosterService.removeGroup(targetStageId, idx);
        }
    }

    private long playerRows(Long stageId) {
        return rosterService.entriesOf(stageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .count();
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, String mode,
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
            c.setFinalRank((long) i);
            c.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
            competitorMapper.insert(c);
        }
    }

    private void settle(Long stageId) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);
    }

}
