package com.dance.street.game.controller;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
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

/**
 * 管理端赛段列表({@code GET /game/stage/list})要下发 {@code awaitingAdvancement} / {@code skipConfirm},
 * 供「开始赛段」按钮按<b>名单来源(出口配置)</b>判断依赖是否就绪 —— 而不是看链上的"上一赛段"。
 *
 * <p>回归:管理端此前只填 {@code incoming},没填这两个字段,侧栏只能用 {@code prevStage.status}
 * 判断,导致并行分支(来源已就绪、但链上前一段还在跑)的"开始赛段"按钮被错误禁用。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageListEnrichmentTest {

    private static final String DB_PATH = "target/stage-list-enrichment.db";

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

    @Autowired private TStageController stageController;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private ITStageService stageService;

    @Test
    void managementStageListCarriesDependencyFlags() {
        Long tid = newTournament("赛段列表依赖标记");
        // 链 S → A;A 的来源是 S(建段默认链式衔接)
        TStageVo s = newStage(tid, "上游", null);
        TStageVo a = newStage(tid, "下游", s.getId());

        // S 已结算,且有一名晋级者 → A 的来源就绪但晋级者未确认
        insertAdvancer(tid, s.getId());
        setSettled(s.getId());

        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        TableDataInfo<TStageVo> page = stageController.list(bo, new PageQuery(100, 1));
        List<TStageVo> stages = page.getData();
        assertNotNull(stages);

        TStageVo aVo = stages.stream().filter(x -> x.getId().equals(a.getId())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, aVo.getAwaitingAdvancement(),
            "来源已结束且有候选的下游赛段应标记为「待确认晋级」");
        assertNotNull(aVo.getSkipConfirm(), "管理端列表应下发 skipConfirm(与导播台口径一致)");

        TStageVo sVo = stages.stream().filter(x -> x.getId().equals(s.getId())).findFirst().orElseThrow();
        assertEquals(null, sVo.getAwaitingAdvancement(),
            "入口赛段没有内部来源,不应被判为待确认晋级");
    }

    // ------------------------------------------------------------------

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tid, String name, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(2L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        return stageService.insertByBo(bo);
    }

    private void insertAdvancer(Long tid, Long stageId) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName("晋级者");
        c.setOutcomeStatus(OutcomeStatusEnum.ADVANCE.getCode());
        competitorMapper.insert(c);
    }

    private void setSettled(Long stageId) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(upd);
    }
}
