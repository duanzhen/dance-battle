package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.roster.RosterMaterializer;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据安全加固的回归:
 * <ul>
 *   <li>t_competitor 唯一索引 (stage_id, source_competitor_id):同源重复物化被库拦下,NULL 来源不受限;</li>
 *   <li>物化遇到并发重复(命中唯一索引)按"已物化"处理,不抛错、不重复建成员行;</li>
 *   <li>配置入口不改赛段状态。</li>
 * </ul>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class CompetitorSourceUniqueTest {

    private static final String DB_PATH = "target/competitor-source-unique.db";

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
    @Autowired private TCompetitorMemberMapper memberMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private ITStageService stageService;
    @Autowired private RosterMaterializer materializer;

    @Test
    void databaseRejectsSecondCompetitorForSameSourceButAllowsNullSource() {
        Long tid = newTournament("uk-db");
        TStageVo target = newStage(tid, "目标");
        TCompetitor src = insertCompetitor(tid, target.getId(), null, "src");
        insertCompetitor(tid, target.getId(), null, "null-1");
        insertCompetitor(tid, target.getId(), null, "null-2");
        insertCompetitor(tid, target.getId(), src.getId(), "first");

        assertThrows(RuntimeException.class,
            () -> insertCompetitor(tid, target.getId(), src.getId(), "dup"),
            "同赛段同来源不允许第二行");
        // 换一个赛段,同一个来源可以再物化一次(唯一性按赛段隔离)
        TStageVo other = newStage(tid, "另一赛段");
        insertCompetitor(tid, other.getId(), src.getId(), "other-stage");
    }

    @Test
    void materializeIsIdempotentWhenSourceAlreadyCreatedInTarget() {
        Long tid = newTournament("uk-materialize");
        TStageVo from = newStage(tid, "来源");
        TStageVo to = newStage(tid, "目标");
        TCompetitor source = insertCompetitor(tid, from.getId(), null, "S");
        source.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        TCompetitorMember sm = new TCompetitorMember();
        sm.setTenantId(source.getTenantId());
        sm.setTournamentId(tid);
        sm.setCompetitorId(source.getId());
        sm.setPlayerId(777L);
        sm.setRole("MEMBER");
        memberMapper.insert(sm);

        TStage target = stageMapper.selectById(to.getId());
        Set<Long> occupied = new HashSet<>();
        assertEquals(1, materializer.copyIntoStage(target, source, occupied, List.of(), null));
        // 模拟另一个并发的"确认名单"带着同一个来源再来一遍:命中唯一索引,按已物化处理
        Set<Long> occupied2 = new HashSet<>();
        assertEquals(0, materializer.copyIntoStage(target, source, occupied2, List.of(), null),
            "重复物化应返回 0 而不是抛错");
        assertTrue(occupied2.isEmpty(), "重复物化不应占座");

        List<TCompetitor> rows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, to.getId()));
        assertEquals(1, rows.size(), "目标赛段只应有一行");
        assertEquals(1, memberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, rows.get(0).getId())), "成员行不能重复");
    }

    @Test
    void duplicateDetectionRecognisesSpringAndSqliteStyleErrors() {
        assertTrue(RosterMaterializer.isDuplicateKey(new DuplicateKeyException("x")));
        assertTrue(RosterMaterializer.isDuplicateKey(new RuntimeException("wrap",
            new java.sql.SQLException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: t_competitor.stage_id"))));
        assertTrue(RosterMaterializer.isDuplicateKey(new RuntimeException("wrap",
            new java.sql.SQLException("Duplicate entry '1-2' for key 'uk'", "23000", 1062))));
        org.junit.jupiter.api.Assertions.assertFalse(RosterMaterializer.isDuplicateKey(new RuntimeException("other")));
    }

    @Test
    void updateConfigNeverChangesStatus() {
        Long tid = newTournament("uk-status");
        TStageVo stage = newStage(tid, "配置");
        TStage gaming = new TStage();
        gaming.setId(stage.getId());
        gaming.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(gaming);

        // 前端回传的 status 与库一致:放行,改名生效,状态不动
        TStageConfigBo same = new TStageConfigBo();
        same.setId(stage.getId());
        same.setName("改名后");
        same.setStatus(StageConstants.STAGE_GAMING);
        stageService.updateConfig(same);
        TStage after = stageMapper.selectById(stage.getId());
        assertEquals("改名后", after.getName());
        assertEquals(StageConstants.STAGE_GAMING, after.getStatus());

        // 前端回传的 status 与库不一致(过期副本/越权):拒绝,状态不动
        TStageConfigBo stale = new TStageConfigBo();
        stale.setId(stage.getId());
        stale.setName("不该生效");
        stale.setStatus(StageConstants.STAGE_DRAFT);
        assertThrows(ServiceException.class, () -> stageService.updateConfig(stale));
        TStage still = stageMapper.selectById(stage.getId());
        assertEquals(StageConstants.STAGE_GAMING, still.getStatus());
        assertEquals("改名后", still.getName());

        // 不传 status:同样不动状态
        TStageConfigBo none = new TStageConfigBo();
        none.setId(stage.getId());
        none.setName("再改名");
        stageService.updateConfig(none);
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stage.getId()).getStatus());
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(4L);
        bo.setTeamCountEnd(2L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":4,\"advanceCount\":2,\"pairingMode\":\"SEED\"}}");
        return stageService.insertByBo(bo);
    }

    private TCompetitor insertCompetitor(Long tid, Long stageId, Long sourceId, String name) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setSourceCompetitorId(sourceId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(name);
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
        return c;
    }
}
