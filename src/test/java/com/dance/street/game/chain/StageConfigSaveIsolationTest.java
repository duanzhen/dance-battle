package com.dance.street.game.chain;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.impl.StageChain;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「改配置」与「改链」必须互不牵连。
 *
 * <p>回归的事故:配置面板保存海选圈的裁判(纯配置),后端却在同一个事务里顺带重写了
 * 整条赛段链——先把下游的 prev 置空再接线,多写 3~4 行 t_stage,还按客户端手里那份
 * 可能过期的指针把链写歪;在 SQLite 上这条路径正是 SQLITE_BUSY 的爆点。</p>
 *
 * <p>三条用例对应三层修复:</p>
 * <ol>
 *   <li>配置入口(updateConfig)不写下游行,也不改任何指针;</li>
 *   <li>配置更新入口(updateByBo)的 BO 里没有 prev/next 字段,过期指针根本表达不出来;</li>
 *   <li>改链走意图接口:插入按 afterStageId、移动按 moveStageAfter,顺序由后端推导。</li>
 * </ol>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class StageConfigSaveIsolationTest {

    private static final String DB_PATH = "target/stage-config-isolation.db";

    /** 下游行的哨兵更新时间:配置保存只要碰过那一行,MetaObjectHandler 就会把它冲掉 */
    private static final Date SENTINEL = new Date(946684800000L);

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

    private TStage reload(Long stageId) {
        return stageMapper.selectById(stageId);
    }

    private Long newTournament(String name) {
        TTournament tournament = new TTournament();
        tournament.setName(name);
        tournamentMapper.insert(tournament);
        return tournament.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, String mode, Long afterStageId) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(8L);
        bo.setTeamCountEnd(4L);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        return stageService.insertByBo(bo);
    }

    /** 给某行的 update_time 打哨兵:配置保存若碰了这行,哨兵必定被冲掉 */
    private void markUpdateTime(Long stageId) {
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, stageId)
            .set(TStage::getUpdateTime, SENTINEL));
    }

    private List<Long> chainIds(Long tournamentId) {
        return stageChain.orderedChain(tournamentId).stream().map(TStage::getId).toList();
    }

    @Test
    void configSaveTouchesNothingButItsOwnRow() {
        Long tid = newTournament("配置隔离赛事");
        TStageVo a = newStage(tid, "海选", "AUDITION", null);
        TStageVo b = newStage(tid, "32强", "KNOCKOUT", a.getId());

        TStage aBefore = reload(a.getId());
        TStage bBefore = reload(b.getId());
        markUpdateTime(b.getId());

        // 纯配置保存:改名 + 换规则配置(等价于前端"改海选圈的裁判")
        TStageConfigBo config = new TStageConfigBo();
        config.setId(a.getId());
        config.setName("海选(改)");
        config.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":4}");
        TStageVo updated = stageService.updateConfig(config);

        assertEquals("海选(改)", reload(a.getId()).getName(), "配置字段应写入");
        assertEquals(a.getId(), updated.getId());
        assertEquals(aBefore.getPrevStageId(), reload(a.getId()).getPrevStageId(), "本赛段 prev 不应被配置保存改动");
        assertEquals(aBefore.getNextStageId(), reload(a.getId()).getNextStageId(), "本赛段 next 不应被配置保存改动");

        TStage bAfter = reload(b.getId());
        assertEquals(bBefore.getPrevStageId(), bAfter.getPrevStageId(), "下游 prev 不应被改写");
        assertEquals(bBefore.getNextStageId(), bAfter.getNextStageId(), "下游 next 不应被改写");
        assertEquals(SENTINEL, bAfter.getUpdateTime(), "纯配置保存不应写下游赛段行(此前先置空下游 prev、再写回)");
    }

    @Test
    void configUpdateCannotCarryChainPointers() {
        Long tid = newTournament("过期指针赛事");
        TStageVo a = newStage(tid, "海选", "AUDITION", null);
        TStageVo b = newStage(tid, "32强", "KNOCKOUT", a.getId());
        TStageVo c = newStage(tid, "16强", "KNOCKOUT", b.getId());

        // 配置更新入口的 BO 里没有前后指针字段:客户端手里那份副本再过期也表达不出来
        TStageBo stale = new TStageBo();
        stale.setId(b.getId());
        stale.setTournamentId(tid);
        stale.setName("32强(改)");
        stageService.updateByBo(stale);

        assertEquals("32强(改)", reload(b.getId()).getName(), "配置字段仍应写入");
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "过期指针不得写回:改链只走意图接口");
        assertEquals(b.getId(), reload(a.getId()).getNextStageId(), "上游 next 不应被动过");
        assertEquals(c.getId(), reload(b.getId()).getNextStageId(), "本赛段 next 不应被动过");
        assertEquals(b.getId(), reload(c.getId()).getPrevStageId(), "下游 prev 不应被动过");
    }

    @Test
    void insertAndMoveFollowIntent() {
        Long tid = newTournament("链意图赛事");
        TStageVo a = newStage(tid, "海选", "AUDITION", null);
        TStageVo b = newStage(tid, "16强", "KNOCKOUT", a.getId());

        // 中间插入:插到 A 之后 → A→Z→B
        TStageVo z = newStage(tid, "32强", "KNOCKOUT", a.getId());
        assertEquals(List.of(a.getId(), z.getId(), b.getId()), chainIds(tid), "应插在 A 与 B 之间");
        assertEquals(z.getId(), reload(b.getId()).getPrevStageId(), "B 的前驱应为新插入的 Z");
        assertEquals(a.getId(), reload(z.getId()).getPrevStageId(), "Z 的前驱应为 A");
        assertEquals(b.getId(), reload(z.getId()).getNextStageId(), "Z 的后继应为 B");
        assertEquals(z.getId(), reload(a.getId()).getNextStageId(), "A 的后继应为 Z");
        assertEquals(a.getId(), stageChain.prevOf(reload(z.getId())).getId(), "链推导应与 prev 列一致");

        // 移动:B 移到链头 → B→A→Z
        stageService.moveStageAfter(b.getId(), null);
        assertEquals(List.of(b.getId(), a.getId(), z.getId()), chainIds(tid), "B 应被移到链头");
        assertNull(reload(b.getId()).getPrevStageId(), "链头的 prev 应为空");
        assertEquals(b.getId(), reload(a.getId()).getPrevStageId(), "A 的前驱应变成 B");
        assertEquals(a.getId(), reload(z.getId()).getPrevStageId(), "Z 的前驱应仍是 A");
        assertEquals(z.getId(), reload(a.getId()).getNextStageId(), "A 的后继应仍是 Z");

        // 移到自己后面:直接拒绝,不产生半条链
        assertThrows(ServiceException.class, () -> stageService.moveStageAfter(a.getId(), a.getId()));
        assertEquals(List.of(b.getId(), a.getId(), z.getId()), chainIds(tid), "失败的移动不应改变链");
    }

    @Test
    void deleteSplicesChainThroughStageChain() {
        Long tid = newTournament("删除收口赛事");
        TStageVo a = newStage(tid, "海选", "AUDITION", null);
        TStageVo z = newStage(tid, "32强", "KNOCKOUT", a.getId());
        TStageVo b = newStage(tid, "16强", "KNOCKOUT", z.getId());

        stageService.deleteWithValidByIds(List.of(z.getId()), true);

        assertEquals(List.of(a.getId(), b.getId()), chainIds(tid), "删除中间赛段后链应被前后邻居跨过");
        assertEquals(b.getId(), reload(a.getId()).getNextStageId(), "上游 next 应直接指向下游");
        assertEquals(a.getId(), reload(b.getId()).getPrevStageId(), "下游 prev 应直接指向上游");
    }

}
