package com.dance.street.game.roster;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段增删时的边规则(批次 1):
 *
 * <ul>
 *   <li>删段:被删段的<b>出边</b>删除、<b>入边</b>原地改挂到链上后继(a→b→c 删 b → c 得 a→c);</li>
 *   <li>含<b>自定义(固定)边</b>的赛段不允许直接删除;</li>
 *   <li>进行中/已结束的赛段,它的<b>出边</b>也不允许编辑(来源段必须是 DRAFT)。</li>
 * </ul>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class StageEdgeRulesTest {

    private static final String DB_PATH = "target/stage-edge-rules.db";

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
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;

    /** a→b→c 全是自动边,删 b:c 的来源改挂成 a(原地改),b 的出边删除。 */
    @Test
    void deletingMiddleStageMovesItsInEdgeToSuccessor() {
        Long tid = newTournament("删中间段");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStageVo c = newStage(tid, "C", b.getId());
        assertEquals(a.getId(), rosterService.groupsOfStage(b.getId()).get(0).getSourceStageId());
        assertEquals(b.getId(), rosterService.groupsOfStage(c.getId()).get(0).getSourceStageId());

        stageService.deleteWithValidByIds(List.of(b.getId()), true);

        List<TStageRosterGroupBo> cGroups = rosterService.groupsOfStage(c.getId());
        assertEquals(1, cGroups.size(), "c 应只剩一条来源");
        assertEquals(a.getId(), cGroups.get(0).getSourceStageId(),
            "被删段 b 的入边(a→b)应原地改挂到后继 c → a→c");
        assertEquals(Integer.valueOf(1), cGroups.get(0).getGenerated(), "改挂后仍是自动边");
        assertTrue(rosterService.listBySource(b.getId()).isEmpty(), "被删段 b 的出边应删除");
    }

    /** 含自定义(固定)边的赛段不允许直接删除。 */
    @Test
    void stageWithCustomEdgeCannotBeDeleted() {
        Long tid = newTournament("含自定义边不可删");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        // 给 B 加一条人工固定边(与默认不同规则)
        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(a.getId());
        g.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        g.setFillMode("AUTO");
        g.setQuota(0);
        bo.setGroups(List.of(g));
        rosterService.addGroups(b.getId(), bo);

        assertThrows(ServiceException.class,
            () -> stageService.deleteWithValidByIds(List.of(b.getId()), true),
            "含自定义边的赛段不能直接删除");
    }

    /** 上游已结算 → 不允许再增删改它的出口(来源段必须是 DRAFT)。 */
    @Test
    void terminalSourceEdgeCannotBeEdited() {
        Long tid = newTournament("已结束段出边锁定");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStage upd = new TStage();
        upd.setId(a.getId());
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);

        TStageRosterBo bo = new TStageRosterBo();
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(a.getId());
        g.setResultFilter(OutcomeStatusEnum.ELIMINATED.getCode());
        g.setFillMode("AUTO");
        g.setQuota(0);
        bo.setGroups(List.of(g));
        assertThrows(ServiceException.class, () -> rosterService.addGroups(b.getId(), bo),
            "来源段已结束,不能再给它加出口");

        Long defaultGroupId = rosterService.groupsOfStage(b.getId()).get(0).getId();
        assertThrows(ServiceException.class, () -> rosterService.removeGroup(b.getId(), defaultGroupId),
            "来源段已结束,不能删它的出口");
    }

    /**
     * 海选来源:默认出口按圈生成固定边(generated=0,不随链动);一个圈也是圈;未配圈按 1 圈 ZONE-1。
     */
    @Test
    void auditionSourceGetsPerCircleFixedExit() {
        Long tid = newTournament("海选按圈出口");
        TStageVo audition = newStage(tid, "海选", "AUDITION", 0L, 6L, null,
            "{\"mode\":\"AUDITION\",\"circles\":2,\"advanceCount\":6,\"circleAdvanceCounts\":[3,3]}");
        TStageVo target = newStage(tid, "16强", "KNOCKOUT", 6L, 3L, audition.getId(), null);

        List<TStageRosterGroupBo> groups = rosterService.groupsOfStage(target.getId());
        assertEquals(2, groups.size(), "两圈海选应生成两条按圈边");
        assertTrue(groups.stream().allMatch(g -> Integer.valueOf(0).equals(g.getGenerated())),
            "海选按圈边是固定边(generated=0)");
        assertEquals(java.util.Set.of("ZONE-1", "ZONE-2"),
            groups.stream().map(TStageRosterGroupBo::getZone).collect(java.util.stream.Collectors.toSet()));
        assertTrue(groups.stream().allMatch(g -> Boolean.TRUE.equals(g.getRankByZone())
                && Integer.valueOf(1).equals(g.getRankStart())),
            "按圈边应取圈内名次 1..该圈名额");

        // 未配圈的海选:按 1 圈 ZONE-1 生成固定边
        TStageVo audition2 = newStage(tid, "海选2", "AUDITION", 0L, 4L, target.getId(), null);
        TStageVo target2 = newStage(tid, "32强", "KNOCKOUT", 4L, 2L, audition2.getId(), null);
        List<TStageRosterGroupBo> groups2 = rosterService.groupsOfStage(target2.getId());
        assertEquals(1, groups2.size(), "未配圈时按 1 圈生成");
        assertEquals("ZONE-1", groups2.get(0).getZone());
        assertEquals(Integer.valueOf(0), groups2.get(0).getGenerated());
    }

    // ===== 工具 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name, Long afterStageId) {
        return newStage(tid, name, "KNOCKOUT", 4L, 2L, afterStageId, null);
    }

    private TStageVo newStage(Long tid, String name, String mode, Long start, Long end,
                              Long afterStageId, String ruleConfig) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        bo.setRuleConfig(ruleConfig != null ? ruleConfig
            : "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":" + start + ",\"advanceCount\":" + end
                + ",\"pairingMode\":\"SEQUENTIAL\"}}");
        return stageService.insertByBo(bo);
    }
}
