package com.dance.street.game.stage;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除单个赛段的后续收口:
 * <ul>
 *   <li>删链头时,入口赛段的签到来源组要顺延给新链头(否则新链头开不了赛);</li>
 *   <li>删除一次只允许一个赛段,传多个直接拒绝。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageDeleteFollowUpTest {

    private static final String DB_PATH = "target/stage-delete-followup.db";

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

    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;

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

    private List<TStageRosterGroupBo> groupsOf(Long stageId) {
        return rosterService.listByTarget(stageId).stream()
            .map(TStageRosterVo::getGroups)
            .findFirst()
            .orElse(List.of());
    }

    @Test
    void deletingHeadPassesCheckinSourceToNewHead() {
        Long tid = newTournament("删链头");
        TStageVo a = newStage(tid, "海选", null);
        TStageVo b = newStage(tid, "决赛", a.getId());

        assertTrue(stageService.deleteWithValidByIds(List.of(a.getId()), true));

        List<TStageRosterGroupBo> groups = groupsOf(b.getId());
        assertEquals(1, groups.size(), "新链头应被补 1 条来源组");
        assertEquals(null, groups.get(0).getSourceStageId(), "新链头应为签到来源(无上游)");
    }

    @Test
    void deleteRejectsMultipleStages() {
        Long tid = newTournament("禁止批量删除");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> stageService.deleteWithValidByIds(List.of(a.getId(), b.getId()), true),
            "一次传多个赛段应被拒绝");
        assertTrue(ex.getMessage().contains("一次只能删除一个赛段"),
            "错误信息应说明只支持单个删除,实际: " + ex.getMessage());
        assertNotNull(stageMapper.selectById(a.getId()), "被拒后 A 应仍在");
        assertNotNull(stageMapper.selectById(b.getId()), "被拒后 B 应仍在");
    }
}
