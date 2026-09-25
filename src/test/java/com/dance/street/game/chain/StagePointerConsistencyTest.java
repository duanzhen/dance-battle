package com.dance.street.game.chain;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageLifecycleService;
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
import java.util.stream.Collectors;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段指针一致性:prev 列的对称校正(两个方向都以 next 链为准)。
 *
 * <p>开赛守卫此前只拦「prev 列有值、链上无人指向」,反向(链上有前驱、列为空)
 * 没人管,导播台会把「上一赛段」显示成「未知」。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StagePointerConsistencyTest {

    private static final String DB_PATH = "target/stage-pointer-consistency.db";

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
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private StageChain stageChain;

    @Test
    void prevColumnIsReconciledInBothDirections() {
        Long tid = newTournament("指针校正");
        TStageVo a = newStage(tid, "海选", null);
        TStageVo b = newStage(tid, "4强", a.getId());
        TStageVo c = newStage(tid, "决赛", b.getId());

        // 1) 反向缺口:链上有前驱,但 prev 列为空(前端会显示「上一赛段未知」)
        setPrevColumn(b.getId(), null);
        assertNull(reload(b.getId()).getPrevStageId(), "前置条件:B.prev 已清空");
        assertTrue(stageChain.reconcilePrevColumn(reload(b.getId())), "反向缺口应被修正");
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "B.prev 应回填为链上前驱 A");

        // 2) 正向缺口:prev 列指向了链外赛段
        setPrevColumn(c.getId(), a.getId());
        assertTrue(stageChain.reconcilePrevColumn(reload(c.getId())), "正向缺口应被修正");
        assertEquals(b.getId(), reload(c.getId()).getPrevStageId(), "C.prev 应改为链上前驱 B");

        // 3) 已经一致时不动(幂等)
        assertFalse(stageChain.reconcilePrevColumn(reload(c.getId())), "一致时不应产生写入");
    }

    /** 链头被清空 prev 后,链上本就没有前驱 → 校正为 null,而不是报错拦住开赛。 */
    @Test
    void entryStageKeepsNullPrevInsteadOfBlockingStart() {
        Long tid = newTournament("入口赛段");
        TStageVo a = newStage(tid, "海选", null);

        setPrevColumn(a.getId(), 999999L);
        assertEquals(999999L, reload(a.getId()).getPrevStageId(), "前置条件:prev 列有脏值");

        assertTrue(stageChain.reconcilePrevColumn(reload(a.getId())));
        assertNull(reload(a.getId()).getPrevStageId(), "链头的 prev 应被清空");
    }

    /**
     * ruleConfig 里残留的 {@code transition.targetStageId} 不再参与解析:
     * 下一赛段只认 {@code next_stage_id} 链,旧字段既不生效也不再报「两个答案」。
     */
    @Test
    void legacyTransitionConfigIsIgnored() {
        Long tid = newTournament("忽略遗留transition");
        TStageVo a = newStage(tid, "海选", null);
        TStageVo b = newStage(tid, "4强", a.getId());
        TStageVo other = newStage(tid, "另一段", b.getId());

        // 链上 A.next = B,但配置里把晋级目标写成 other
        TStage settled = new TStage();
        settled.setId(a.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        settled.setRuleConfig("{\"mode\":\"AUDITION\",\"advanceCount\":2,"
            + "\"transition\":{\"targetStageId\":" + other.getId() + "}}");
        stageMapper.updateById(settled);

        // 不抛异常(旧实现会因"两个答案"报错),按链 next 装配(无人可带 → 0)
        assertEquals(0, lifecycleService.calculateAdvancement(a.getId()),
            "遗留 transition 字段应被忽略,晋级只按链 next 解析");
    }

    /**
     * 管理端赛段列表的 prev 必须按链推导:流程图用 prevStageId 找头节点并排序整条链,
     * 库里那一列一旦过期(历史脏数据、指针写失败)整张图都会排错。
     */
    @Test
    void stageListDerivesPrevFromChainNotFromColumn() {
        Long tid = newTournament("列表prev推导");
        TStageVo a = newStage(tid, "海选", null);
        TStageVo b = newStage(tid, "4强", a.getId());
        TStageVo c = newStage(tid, "决赛", b.getId());

        // 模拟两种脏法:链头列上有垃圾值、中间列被写空
        setPrevColumn(a.getId(), 999999L);
        setPrevColumn(b.getId(), null);

        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        Map<Long, TStageVo> byId = stageService.queryList(bo).stream()
            .collect(Collectors.toMap(TStageVo::getId, s -> s));

        assertNull(byId.get(a.getId()).getPrevStageId(), "入口赛段的 prev 应为 null,不受列脏值影响");
        assertEquals(a.getId(), byId.get(b.getId()).getPrevStageId(), "B 的 prev 应按链回填为 A");
        assertEquals(b.getId(), byId.get(c.getId()).getPrevStageId(), "C 的 prev 应为 B");
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
        bo.setTeamCountStart(8L);
        bo.setTeamCountEnd(4L);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        return stageService.insertByBo(bo);
    }

    private TStage reload(Long stageId) {
        return stageMapper.selectById(stageId);
    }

    private void setPrevColumn(Long stageId, Long prevStageId) {
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, stageId)
            .set(TStage::getPrevStageId, prevStageId));
    }
}
