package com.dance.street.game.stage;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除赛段的两个守卫:
 * <ol>
 *   <li>进行中/已结束的赛段不允许删除(此前只在 UI 层禁用,后端无校验);</li>
 *   <li>下游名单已锁定(已装配/已跳过)时,删除上游赛段要被拦住,
 *       不能把它的来源组静默摘空;</li>
 *   <li>解锁后删除可正常完成,且下游按其新链位置补回默认来源组。</li>
 * </ol>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageDeleteGuardTest {

    private static final String DB_PATH = "target/stage-delete-guard.db";

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
    private ITStageService stageService;
    @Autowired
    private ITStageRosterService rosterService;

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
        return stageService.insertByBo(bo);
    }

    private void setStatus(Long stageId, String status) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(status);
        stageMapper.updateById(upd);
    }

    private boolean delete(Long... stageIds) {
        return stageService.deleteWithValidByIds(List.of(stageIds), true);
    }

    @Test
    void draftStageCanBeDeleted() {
        Long tid = newTournament("草稿赛段可删");
        TStageVo stage = newStage(tid, "海选", null);

        assertTrue(delete(stage.getId()), "草稿赛段应可删除");
        assertNull(stageMapper.selectById(stage.getId()), "赛段应已删除");
    }

    @Test
    void gamingStageCannotBeDeleted() {
        Long tid = newTournament("进行中赛段不可删");
        TStageVo stage = newStage(tid, "海选", null);
        setStatus(stage.getId(), StageConstants.STAGE_GAMING);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> delete(stage.getId()), "进行中的赛段不允许删除");
        assertTrue(ex.getMessage().contains("进行中"), "错误信息应说明原因,实际: " + ex.getMessage());
        assertNotNull(stageMapper.selectById(stage.getId()), "被拒后赛段应仍然存在");
    }

    @Test
    void settledStageCannotBeDeleted() {
        Long tid = newTournament("已结束赛段不可删");
        TStageVo stage = newStage(tid, "决赛", null);
        setStatus(stage.getId(), StageConstants.STAGE_SETTLED);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> delete(stage.getId()), "已结束的赛段不允许删除");
        assertTrue(ex.getMessage().contains("已结束"), "错误信息应说明原因,实际: " + ex.getMessage());
        assertNotNull(stageMapper.selectById(stage.getId()), "被拒后赛段应仍然存在");
    }

    @Test
    void lockedDownstreamBlocksUpstreamDelete() {
        Long tid = newTournament("锁名单不可删上游");
        TStageVo upstream = newStage(tid, "海选", null);
        TStageVo downstream = newStage(tid, "决赛", upstream.getId());

        // 建链时下游自动生成了指向海选的默认来源组
        List<TStageRosterGroupBo> groups = rosterService.listByTarget(downstream.getId()).stream()
            .map(TStageRosterVo::getGroups).findFirst().orElse(List.of());
        assertEquals(1, groups.size(), "下游应有 1 条默认名单来源组");
        assertEquals(upstream.getId(), groups.get(0).getSourceStageId(), "来源组应指向海选");

        // 锁定下游名单(已跳过),此时删除上游会破坏它的名单来源
        rosterService.markSkipped(downstream.getId());
        ServiceException ex = assertThrows(ServiceException.class,
            () -> delete(upstream.getId()), "下游名单已锁定时不允许删除上游");
        assertTrue(ex.getMessage().contains("请先重置该赛段后再删除"),
            "错误信息应给出可执行的动作,实际: " + ex.getMessage());
        assertNotNull(stageMapper.selectById(upstream.getId()), "被拒后上游应仍然存在");
        // 被拒时整个删除事务必须回滚:链表指针不能被 reconnectChainBeforeDelete 改坏
        assertEquals(upstream.getId(), stageMapper.selectById(downstream.getId()).getPrevStageId(),
            "被拒后下游的 prev 指针应保持不变");

        // 解锁后可正常删除:拦截是可恢复的,不是死路
        rosterService.resetByTarget(downstream.getId());
        assertTrue(delete(upstream.getId()), "解锁后应可删除上游");

        // 下游成为链头,删除后补齐流程应给它补回签到来源组(而不是留下空名单)
        List<TStageRosterGroupBo> after = rosterService.listByTarget(downstream.getId()).stream()
            .map(TStageRosterVo::getGroups).findFirst().orElse(List.of());
        assertEquals(1, after.size(), "下游应被补回 1 条来源组");
        assertNull(after.get(0).getSourceStageId(), "下游成为入口赛段后来源组应为签到(无上游)");
    }
}
