package com.dance.street.game.chain;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 赛段指针口径:库里只有 next 链这一份事实,前驱一律推导。
 *
 * <p>原 {@code prev_stage_id} 列已删除,"上一赛段"不再落库,因此也不存在
 * 「列与链不同步」这类需要双向校正的状态——这里守住的是展示字段按链推导、
 * 以及遗留 transition 配置不再参与解析。</p>
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
     * 管理端赛段列表(以及单段详情)的 prev 必须按链推导:流程图用 prevStageId 找头节点
     * 并排序整条链,库里已经没有这一列,它只能是推导值。
     */
    @Test
    void stageListDerivesPrevFromChain() {
        Long tid = newTournament("列表prev推导");
        TStageVo a = newStage(tid, "海选", null);
        TStageVo b = newStage(tid, "4强", a.getId());
        TStageVo c = newStage(tid, "决赛", b.getId());

        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        Map<Long, TStageVo> byId = stageService.queryList(bo).stream()
            .collect(Collectors.toMap(TStageVo::getId, s -> s));

        assertNull(byId.get(a.getId()).getPrevStageId(), "入口赛段的 prev 应为 null");
        assertEquals(a.getId(), byId.get(b.getId()).getPrevStageId(), "B 的 prev 应按链回填为 A");
        assertEquals(b.getId(), byId.get(c.getId()).getPrevStageId(), "C 的 prev 应为 B");
        // 单段详情同一口径(大屏对战树、导播台都走它)
        assertEquals(b.getId(), stageService.queryById(c.getId()).getPrevStageId(),
            "单段详情的 prev 也应按链推导");
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

}
