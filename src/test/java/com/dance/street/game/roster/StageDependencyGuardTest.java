package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖以"来源组(边)"为准之后的两条硬规则:
 *
 * <ol>
 *   <li>开赛守卫 = 来源的边必须都结束:链上前一段还在跑不拦,只要自己那几条来源都结算
 *       并确认名单就能开(多赛段同时进行的前提);反过来,来源没结束照样拦。</li>
 *   <li>连边方向:只能把"链上排在自己后面"的赛段作为去向,反向连边被拦(顺序即拓扑序,天然不成环)。</li>
 * </ol>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class StageDependencyGuardTest {

    private static final String DB_PATH = "target/stage-dependency-guard.db";

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
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITStageRosterService rosterService;

    /**
     * 并行分支:链上前一段还在跑,但只要目标赛段的来源都结算了,就能开赛。
     *
     * <p>链顺序 A → Z → B,但 B 的来源是 A(不是 Z)。旧口径按"链上前一段(Z)"判断,
     * Z 还在跑就永远开不了 B;新口径只看来源边,B 可以正常开。</p>
     */
    @Test
    void stageStartsWhenItsOwnSourcesSettledEvenIfChainNeighbourStillRunning() {
        Long tid = newTournament("并行开赛守卫");
        TStageVo a = newStage(tid, "A", null);
        TStageVo z = newStage(tid, "Z", a.getId());
        TStageVo b = newStage(tid, "B", z.getId());

        // 把 B 的来源换成 A(先加 A 的边,再删掉建段自动补的"Z→B"衔接边),模拟分支
        TStageRosterBo fromA = new TStageRosterBo();
        fromA.setSourceStageId(a.getId());
        fromA.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        fromA.setFillMode("AUTO");
        rosterService.addGroups(b.getId(), fromA);
        Long generated = rosterService.groupsOfStage(b.getId()).stream()
            .filter(g -> Integer.valueOf(1).equals(g.getGenerated()))
            .map(TStageRosterGroupBo::getId)
            .findFirst().orElse(null);
        if (generated != null) {
            rosterService.removeGroup(b.getId(), generated);
        }

        // A 结算并给 B 落一个晋级者,确认名单;Z 仍在进行中(链上前一段没打完)
        Long advancer = insertCompetitor(tid, a.getId(), "甲", OutcomeStatusEnum.ADVANCE.getCode(), 1L);
        settle(a.getId());
        settle(z.getId());
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, z.getId()).set(TStage::getStatus, StageConstants.STAGE_GAMING));
        rosterService.applyRoster(b.getId(), null);

        assertNotNull(competitorMapper.selectById(advancer));
        assertDoesNotThrow(() -> lifecycleService.startStage(b.getId()),
            "来源(A)已结束就应能开 B,不受链上前一段(Z)未结束影响");
    }

    /** 反过来:来源没结束就必须拦下,且报错要点名是哪几段。 */
    @Test
    void stageBlockedWhenAnySourceNotSettled() {
        Long tid = newTournament("来源未结束拦截");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> lifecycleService.startStage(b.getId()));
        assertTrue(ex.getMessage().contains("名单来源") && ex.getMessage().contains("A"),
            "报错应点名未结束的来源赛段,实际: " + ex.getMessage());
    }

    /** 连边方向:只能往后连(直接反向、间接反向都拦),自身来源也拦。 */
    @Test
    void backwardSourceIsRejected() {
        Long tid = newTournament("连边方向");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStageVo c = newStage(tid, "C", b.getId());

        ServiceException direct = assertThrows(ServiceException.class,
            () -> rosterService.addGroups(a.getId(), sourceBo(b.getId())));
        assertTrue(direct.getMessage().contains("必须排在"), "应拦直接反向边: " + direct.getMessage());

        // 跨级反向也拦:C 排在 A 之后,不能当 A 的来源
        ServiceException indirect = assertThrows(ServiceException.class,
            () -> rosterService.addGroups(a.getId(), sourceBo(c.getId())));
        assertTrue(indirect.getMessage().contains("必须排在"), "应拦跨级反向边: " + indirect.getMessage());

        ServiceException selfLoop = assertThrows(ServiceException.class,
            () -> rosterService.addGroups(a.getId(), sourceBo(a.getId())));
        assertTrue(selfLoop.getMessage().contains("自身"), "应拦自身来源: " + selfLoop.getMessage());

        // 正向跨级(海选 → 决赛这种直入)是允许的
        assertDoesNotThrow(() -> rosterService.addGroups(c.getId(), sourceBo(a.getId())),
            "来源排在目标之前即可跨级连边");
    }

    /**
     * 跳过中间态只装配「本赛段自己」:兄弟赛段已经开赛,也不能影响本赛段确认名单。
     *
     * <p>链顺序 A→B→C,但 C 的来源是 A(分支 A→B / A→C)。A 结算后 C 先确认名单并进入进行中;
     * 此时对 B 跳过中间态,旧实现按"链上前驱 A"扇出给 A 的所有下游,会去装配已在 GAMING 的 C
     * 而直接报错(本赛段反而开不了);新实现只装配 B 自己,C 保持不动。</p>
     */
    @Test
    void skippingIntermediateRosterOnlyMaterializesOwnStage() {
        Long tid = newTournament("跳过中间态只装本段");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStageVo c = newStage(tid, "C", b.getId());

        // 把 C 的来源换成 A(先加 A→C 边,再删掉建段自动补的"B→C"衔接边),形成分支 A→B / A→C
        rosterService.addGroups(c.getId(), sourceBo(a.getId()));
        Long generated = rosterService.groupsOfStage(c.getId()).stream()
            .filter(g -> Integer.valueOf(1).equals(g.getGenerated()))
            .map(TStageRosterGroupBo::getId)
            .findFirst().orElse(null);
        if (generated != null) {
            rosterService.removeGroup(c.getId(), generated);
        }

        // A 结算并给出两名晋级者
        insertCompetitor(tid, a.getId(), "甲", OutcomeStatusEnum.ADVANCE.getCode(), 1L);
        insertCompetitor(tid, a.getId(), "乙", OutcomeStatusEnum.ADVANCE.getCode(), 2L);
        settle(a.getId());

        // C 先跳过中间态确认名单,并进入进行中(模拟两段并行,C 已在 GAMING)
        assertEquals(2, lifecycleService.confirmStageRoster(c.getId()));
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, c.getId()).set(TStage::getStatus, StageConstants.STAGE_GAMING));

        // B 再跳过中间态:旧口径会因 C(M 的兄弟下游)已在 GAMING 抛错,新口径只装配 B 自己
        assertDoesNotThrow(() -> lifecycleService.confirmStageRoster(b.getId()),
            "跳过中间态只应装配本赛段,不能被兄弟赛段的进行状态拦住");
        assertEquals(2, competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, b.getId())));
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(c.getId()).getStatus());
        assertEquals(2, competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, c.getId())),
            "装配 B 不应改动兄弟赛段 C 的名单");
    }

    // ===== 工具 =====

    private TStageRosterBo sourceBo(Long sourceStageId) {
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        bo.setFillMode("AUTO");
        return bo;
    }

    private void settle(Long stageId) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);
    }

    private Long insertCompetitor(Long tid, Long stageId, String name, String outcome, Long rank) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber("1");
        c.setSeedRank(rank);
        c.setFinalRank(rank);
        c.setOutcomeStatus(outcome);
        competitorMapper.insert(c);
        return c.getId();
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(4L);
        bo.setTeamCountEnd(2L);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":4,"
            + "\"advanceCount\":2,\"pairingMode\":\"SEQUENTIAL\"}}");
        return stageService.insertByBo(bo);
    }
}
