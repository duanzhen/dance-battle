package com.dance.street.game.chain;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.StageChain;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段链口径测试:next 链是唯一事实源,prev 列仅作展示字段。
 *
 * <p>覆盖三件事:正常链上 head/prev/顺序推导正确且 prev 列照常写入;
 * 两个指针不同步时以 next 链为准;next 链断开时该赛段按入口处理。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageChainTest {

    private static final String DB_PATH = "target/stage-chain.db";

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
    private StageChain stageChain;

    private TStageBo baseStage(Long tournamentId, String name, Long start, Long end, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        return bo;
    }

    private TStage reload(Long stageId) {
        return stageMapper.selectById(stageId);
    }

    private void setPrevColumn(Long stageId, Long prevStageId) {
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, stageId)
            .set(TStage::getPrevStageId, prevStageId));
    }

    private void clearNextColumn(Long stageId) {
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, stageId)
            .set(TStage::getNextStageId, null));
    }

    @Test
    void nextChainIsSourceOfTruth() {
        TTournament tournament = new TTournament();
        tournament.setName("链口径赛事");
        tournamentMapper.insert(tournament);

        TStageVo a = stageService.insertByBo(baseStage(tournament.getId(), "海选", 8L, 4L, null));
        TStageVo b = stageService.insertByBo(baseStage(tournament.getId(), "4强", 4L, 2L, a.getId()));
        TStageVo c = stageService.insertByBo(baseStage(tournament.getId(), "决赛", 2L, 1L, b.getId()));

        // 1) 正常链:head / prev / 顺序都按 next 链推导
        assertEquals(a.getId(), stageChain.headOf(tournament.getId()).getId(), "链头应为 A");
        assertNull(stageChain.prevOf(reload(a.getId())), "A 没有前驱");
        assertEquals(a.getId(), stageChain.prevOf(reload(b.getId())).getId(), "B 的前驱应为 A");
        assertEquals(b.getId(), stageChain.prevOf(reload(c.getId())).getId(), "C 的前驱应为 B");
        assertTrue(stageChain.isEntry(reload(a.getId())), "A 是入口赛段");
        assertFalse(stageChain.isEntry(reload(b.getId())), "B 不是入口赛段");
        assertEquals(List.of(a.getId(), b.getId(), c.getId()),
            stageChain.orderedChain(tournament.getId()).stream().map(TStage::getId).toList(),
            "链顺序应为 A→B→C");

        // 2) prev 列仍照常写入(第 1、2 步不停写,前端与 VO 契约不变)
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "B.prev 列应写入 A");
        assertEquals(b.getId(), reload(c.getId()).getPrevStageId(), "C.prev 列应写入 B");

        // 3) prev 列被改错时:以 next 链为准,C 的前驱仍是 B
        setPrevColumn(c.getId(), a.getId());
        assertEquals(a.getId(), reload(c.getId()).getPrevStageId(), "前置条件:prev 列已改错为 A");
        assertEquals(b.getId(), stageChain.prevOf(reload(c.getId())).getId(),
            "prev 列与 next 链冲突时应以 next 链为准");

        // 4) next 链断开时:该赛段按入口处理,不再信任 prev 列
        clearNextColumn(a.getId());
        assertNull(reload(a.getId()).getNextStageId(), "前置条件:A.next 已断开");
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "前置条件:B.prev 仍指向 A");
        assertNull(stageChain.prevOf(reload(b.getId())), "next 链断开后 B 应被当作入口");
        assertTrue(stageChain.isEntry(reload(b.getId())), "B 此时是入口赛段");
    }

    /** 建链只动指针:插入新赛段不得改前驱的参赛规模/晋级名额(名额由来源组与赛段配置决定)。 */
    @Test
    void insertingStageDoesNotRewritePredecessorQuota() {
        TTournament tournament = new TTournament();
        tournament.setName("名额不联动赛事");
        tournamentMapper.insert(tournament);

        TStageVo a = stageService.insertByBo(baseStage(tournament.getId(), "海选", 32L, 8L, null));
        TStageVo b = stageService.insertByBo(baseStage(tournament.getId(), "复活赛", 16L, 16L, a.getId()));

        assertEquals(32L, reload(a.getId()).getTeamCountStart(), "前驱参赛规模不应被动过");
        assertEquals(8L, reload(a.getId()).getTeamCountEnd(), "前驱晋级名额不应被下游容量顶掉");
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "新赛段应接在前驱之后");
    }
}
