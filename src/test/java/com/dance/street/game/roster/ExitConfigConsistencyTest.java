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
 * 自定义出口配置与中间层名单的一致性。
 *
 * <p>严格规则:上游赛段一旦开赛/结束,它的出口就锁定(不允许再改)。
 * 因此出口一律在<b>上游仍是 DRAFT</b> 时配置,结算后中间层按该规则取人、并随改链重建。</p>
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

    /** 开赛前把出口改成「第 1~2 名」并摘掉整单默认:结算后中间层按该规则取人收敛到 2 人。 */
    @Test
    void customExitConfiguredBeforeStartIsHonored() {
        Long tid = newTournament("exit-config");
        TStageVo audition = newStage(tid, "海选", "AUDITION", 0L, 2L, null);
        insertCompetitors(tid, audition.getId(), 4);
        // 上游还是 DRAFT 时配好下游出口(上游开赛后不允许再改它的出口)
        TStageVo round16 = newStage(tid, "16强", "KNOCKOUT", 4L, 2L, audition.getId());
        rosterService.addGroups(round16.getId(), customExit(audition.getId(), 1, 2));
        removeGeneratedDefault(round16.getId(), audition.getId());
        settle(audition.getId());

        assertEquals(2, playerRows(round16.getId()), "自定义出口(第 1~2 名)应把中间层收敛到 2 人");
        assertEquals(4, rosterService.entriesOf(round16.getId()).size(),
            "座位数仍按计划规模,空位照占号");
    }

    /** 改链后默认边原地跟随新前驱 —— 中间层必须按新来源重建。 */
    @Test
    void linkChangeRebuildsMiddleLayerAfterExitRulesChanged() {
        Long tid = newTournament("exit-config-move");
        TStageVo audition = newStage(tid, "海选", "AUDITION", 0L, 2L, null);
        insertCompetitors(tid, audition.getId(), 4);
        TStageVo round16 = newStage(tid, "16强", "KNOCKOUT", 4L, 2L, audition.getId());
        insertCompetitors(tid, round16.getId(), 4);
        TStageVo revival = newStage(tid, "复活赛", "KNOCKOUT", 8L, 2L, round16.getId());
        // 开赛前给复活赛加自定义出口(来源=海选),此时海选仍是 DRAFT
        rosterService.addGroups(revival.getId(), customExit(audition.getId(), 1, 2));
        settle(audition.getId());
        settle(round16.getId());

        // 复活赛:默认整单接 16强(4 人) + 自定义出口"海选第 1~2 名"(2 人,不同参赛方)= 6 人
        assertEquals(6, playerRows(revival.getId()));

        // 把 16强 移到复活赛之后:复活赛的默认边原地跟随新前驱(海选),
        // 与"海选第 1~2 名"取的是同一批人 → 并集收敛到海选的晋级者
        stageService.moveStageAfter(round16.getId(), revival.getId());

        assertEquals(4, playerRows(revival.getId()),
            "改链后默认边接海选,与自定义出口并集 = 海选的晋级者");
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
        for (TStageRosterGroupBo g : groups) {
            if (java.util.Objects.equals(g.getSourceStageId(), sourceStageId)
                && g.getZone() == null && g.getRankStart() == null && g.getRankEnd() == null) {
                rosterService.removeGroup(targetStageId, g.getId());
                return;
            }
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
