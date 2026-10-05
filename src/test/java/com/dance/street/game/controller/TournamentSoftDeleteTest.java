package com.dance.street.game.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import com.dance.street.game.engine.common.StageConstants;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除赛事 = 赛事主表逻辑删除(软删除),下属数据一律保留。
 *
 * <p>回归:此前删除赛事会级联物理删除赛段/场次/参赛方/打分/场景/控件/裁判/选手。
 * 现在只在 {@code t_tournament} 打删除标记,入口列表不再出现该赛事,但所有下属数据仍在库里。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TournamentSoftDeleteTest {

    private static final String DB_PATH = "target/tournament-soft-delete.db";

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

    @Autowired private ITTournamentService tournamentService;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TPlayerMapper playerMapper;
    @Autowired private TVisSceneMapper visSceneMapper;
    @Autowired private DataSource dataSource;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;

    @Test
    void deleteMarksTournamentOnlyAndKeepsSubordinateData() throws Exception {
        // 建赛会自动生成一个默认场景(下属数据之一)
        TTournamentBo bo = new TTournamentBo();
        bo.setName("软删除赛事");
        bo.setStatus(0L);
        TTournamentVo tournament = tournamentService.insertByBo(bo);
        Long tid = tournament.getId();
        assertNotNull(tid);

        TStage stage = new TStage();
        stage.setTournamentId(tid);
        stage.setName("海选");
        stage.setStageMode("AUDITION");
        stageMapper.insert(stage);

        TPlayer player = new TPlayer();
        player.setTournamentId(tid);
        player.setName("甲");
        playerMapper.insert(player);

        long scenesBefore = sceneCount(tid);
        assertTrue(scenesBefore > 0, "建赛应自动生成默认场景");

        // 删除赛事
        assertTrue(tournamentService.deleteWithValidByIds(List.of(tid), true));

        // 1) 入口列表与按 id 查询都不再出现(逻辑删除对查询生效)
        TableDataInfo<TTournamentVo> page = tournamentService.queryPageList(new TTournamentBo(), new PageQuery(100, 1));
        assertTrue(page.getData().stream().noneMatch(vo -> Objects.equals(vo.getId(), tid)),
            "已删除赛事不应再出现在入口列表");
        assertNull(tournamentService.queryById(tid), "已删除赛事按 id 查询应返回空");

        // 2) 主表行仍在,只是 deleted=1(软删除,非物理删除)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT deleted FROM t_tournament WHERE id = ?")) {
            ps.setLong(1, tid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "赛事主表行应仍在(软删除)");
                assertEquals(1, rs.getInt(1), "赛事主表 deleted 标记应为 1");
            }
        }

        // 3) 下属数据全部保留
        assertNotNull(stageMapper.selectById(stage.getId()), "赛段不应被删除");
        assertNotNull(playerMapper.selectById(player.getId()), "选手不应被删除");
        assertEquals(scenesBefore, sceneCount(tid), "场景不应被删除");
    }

    @Test
    void deleteIsNotBlockedByLockedDownstreamStage() {
        TTournamentBo bo = new TTournamentBo();
        bo.setName("软删除-下游已锁名单");
        bo.setStatus(0L);
        Long tid = tournamentService.insertByBo(bo).getId();

        TStageVo upstream = stageService.insertByBo(newStageBo(tid, "海选", null));
        TStageVo downstream = stageService.insertByBo(newStageBo(tid, "决赛", upstream.getId()));
        // 下游名单已跳过(锁定):删除单个赛段时这会拦住上游,但"删除整个赛事"不该被拦
        rosterService.markSkipped(downstream.getId());

        assertTrue(tournamentService.deleteWithValidByIds(List.of(tid), true),
            "整场软删除不应被赛段级守卫拦住");
        assertNull(tournamentService.queryById(tid), "赛事入口应已不可见");
        assertNotNull(stageMapper.selectById(upstream.getId()), "下属赛段应保留");
        assertNotNull(stageMapper.selectById(downstream.getId()), "下属赛段应保留");
    }

    private TStageBo newStageBo(Long tournamentId, String name, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(4L);
        bo.setTeamCountEnd(2L);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        return bo;
    }

    private long sceneCount(Long tournamentId) {
        return visSceneMapper.selectCount(Wrappers.<TVisScene>lambdaQuery()
            .eq(TVisScene::getTournamentId, tournamentId));
    }
}
