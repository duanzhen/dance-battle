package com.dance.street.game.stage;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 大屏赛程流转的"当前场次"选择口径:
 *
 * <ul>
 *   <li><b>不绑定 stageId</b>:跟随"链上第一个 GAMING 赛段"(全局兜底,保持旧行为);</li>
 *   <li><b>绑定 stageId</b>:只跟随该绑定赛段,即使链上更靠前的赛段也在进行中;</li>
 *   <li>绑定赛段未进行中 → 回 currentStageId 但无当前场次(控件透明);</li>
 *   <li>绑定的赛段不属于本赛事 → 按未绑定处理,回退全局。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageFlowStageBindingTest {

    private static final String DB_PATH = "target/stage-flow-stage-binding.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:sqlite:" + DB_PATH
                + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS"
                + "&journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE&synchronous=NORMAL");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private ITStageService stageService;

    @Test
    void boundStageWinsOverChainFirstWhileUnboundKeepsOldBehavior() {
        Long tid = newTournament("当前场次绑定");
        // 链顺序 A → B,两段同时进行中(多赛段并行)
        TStageVo a = newStage(tid, "分支A", null);
        TStageVo b = newStage(tid, "分支B", a.getId());
        TStageVo draft = newStage(tid, "未开赛C", b.getId());

        setGaming(a.getId());
        setGaming(b.getId());
        TMatch matchA = newMatch(tid, a.getId(), "A场次1");
        TMatch matchB = newMatch(tid, b.getId(), "B场次1");

        // 不绑定:回退链上第一个 GAMING(A)——保持旧行为
        StageFlowVo unbound = stageService.getFlowByTournamentId(tid);
        assertEquals(a.getId(), unbound.getCurrentStageId(), "不绑定应取链上第一个 GAMING 赛段");
        assertNotNull(unbound.getCurrentMatch());
        assertEquals(matchA.getId(), unbound.getCurrentMatch().getId());

        // 绑定 B:即使 A 在链上更靠前且也在进行中,也只跟随 B
        StageFlowVo boundB = stageService.getFlowByTournamentId(tid, b.getId());
        assertEquals(b.getId(), boundB.getCurrentStageId(), "绑定应取绑定的赛段");
        assertNotNull(boundB.getCurrentMatch());
        assertEquals(matchB.getId(), boundB.getCurrentMatch().getId());

        // 绑定未开赛的 C:回 currentStageId 但没有当前场次(控件保持透明)
        StageFlowVo boundDraft = stageService.getFlowByTournamentId(tid, draft.getId());
        assertEquals(draft.getId(), boundDraft.getCurrentStageId());
        assertNull(boundDraft.getCurrentMatch(), "绑定赛段未进行中时不应产出当前场次");

        // 绑定不属于本赛事的赛段:按未绑定处理,回退链首 A
        StageFlowVo boundUnknown = stageService.getFlowByTournamentId(tid, -1L);
        assertEquals(a.getId(), boundUnknown.getCurrentStageId(), "绑定失效应回退链上第一个 GAMING");
        assertEquals(matchA.getId(), boundUnknown.getCurrentMatch().getId());
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

    private void setGaming(Long stageId) {
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(upd);
    }

    private TMatch newMatch(Long tid, Long stageId, String name) {
        TMatch m = new TMatch();
        m.setTournamentId(tid);
        m.setStageId(stageId);
        m.setName(name);
        m.setStatus(StageConstants.MATCH_GAMING);
        m.setMatchMode("STANDARD");
        m.setDisplayRow(0L);
        matchMapper.insert(m);
        return m;
    }
}
