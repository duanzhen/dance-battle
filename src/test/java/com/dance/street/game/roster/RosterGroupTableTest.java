package com.dance.street.game.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名单来源组表化后的核心语义回归:
 *
 * <ul>
 *   <li>人工配置的出口不会因为链变更被当成"过期默认组"删掉(按 generated 出处判断,不看长相);</li>
 *   <li>单一来源的多个出口:新座号 = 来源座号(边之间没有先后语义)。</li>
 * </ul>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class RosterGroupTableTest {

    private static final String DB_PATH = "target/roster-group-table.db";

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
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;

    /**
     * 自定义出口的形状和系统默认衔接完全一样(ADVANCE / 无圈 / 无名次段),但它是人配的。
     * 链变更时只能清理 generated=1 的那条,不能按"长相"把人工出口一起删掉。
     */
    @Test
    void customExitSurvivesChainChange() {
        Long tid = newTournament("链变更不误删出口");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStageVo c = newStage(tid, "C", b.getId());

        // C 上人工配一条"来自 A 的整单晋级"——字段长相与系统默认衔接一模一样
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(a.getId());
        bo.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        bo.setFillMode("AUTO");
        rosterService.addGroups(c.getId(), bo);
        Long customId = rosterService.groupsOfStage(c.getId()).stream()
            .filter(g -> a.getId().equals(g.getSourceStageId()))
            .map(TStageRosterGroupBo::getId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("自定义出口应写入"));

        // 在 B 与 C 之间插一段 → 触发 C 的名单对账(链上前驱从 B 变成新段)
        TStageVo z = newStage(tid, "Z", b.getId());

        List<TStageRosterGroupBo> after = rosterService.groupsOfStage(c.getId());
        assertTrue(after.stream().anyMatch(g -> customId.equals(g.getId())),
            "人工配的出口必须保留(不能按长相当过期默认组删掉),实际=" + after);
        assertTrue(after.stream().noneMatch(g -> Integer.valueOf(1).equals(g.getGenerated())
                && b.getId().equals(g.getSourceStageId())),
            "系统默认衔接的 source 应跟随链前驱,不能再指向旧的 b,实际=" + after);
        // 默认边原地跟随新链前驱 Z(不删除、不重补)
        assertTrue(after.stream().anyMatch(g -> Integer.valueOf(1).equals(g.getGenerated())
                && z.getId().equals(g.getSourceStageId())),
            "系统默认衔接应原地跟随新链前驱 Z,实际=" + after);
    }

    /**
     * 多入边(同一个来源按名次段配了两条出口,也算两条入边):全部进待落座区,新座号=NULL。
     *
     * <p>按边数判定,而不是按"不同来源赛段数"判定——否则 A/B 两圈各自的座号都从 1 起算,
     * 复制来源座号时会撞座、把人顶掉。</p>
     */
    @Test
    void multiInEdgeGoesToHoldingAreaWithNullSeat() {
        Long tid = newTournament("多入边待落座");
        TStageVo source = newAuditionStage(tid, "海选", null);
        TStageVo target = newStage(tid, "16强", source.getId());
        Long first = insertCompetitor(tid, source.getId(), "甲", "1", 1L, OutcomeStatusEnum.ADVANCE.getCode());
        Long second = insertCompetitor(tid, source.getId(), "乙", "2", 2L, OutcomeStatusEnum.ADVANCE.getCode());
        addRankGroup(tid, target.getId(), source.getId(), 1, 1);
        addRankGroup(tid, target.getId(), source.getId(), 2, 2);

        // 来源赛段结算,名单就绪
        TStage settled = new TStage();
        settled.setId(source.getId());
        settled.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(settled);

        rosterService.rebuildEntries(target.getId());
        List<TStageRosterEntry> rows = rosterService.entriesOf(target.getId());
        assertTrue(rows.stream().noneMatch(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind())
                && e.getSlot() != null),
            "多入边不做自动落座,所有人都不占座位号");
        List<Long> holding = rows.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> e.getSlot() == null)
            .map(TStageRosterEntry::getSourceCompetitorId)
            .toList();
        assertEquals(2, holding.size(), "两个人都在待落座区");
        assertTrue(holding.contains(first) && holding.contains(second));
    }

    /**
     * 加了一条人工出口后,应能删掉默认(链式)出口 —— 即便那条默认出口是目标赛段的唯一来源。
     *
     * <p>回归:此前 {@code removeGroup} 有"名单至少需要保留一组来源"的守卫,删最后一条会报错,
     * 且提示的"删除整个来源"操作在 UI 上并不存在,分叉/改线时无法先删旧出口再加新出口。</p>
     */
    @Test
    void removesDefaultExitEvenWhenItIsTheOnlySource() {
        Long tid = newTournament("删默认出口");
        TStageVo source = newStage(tid, "上游", null);
        TStageVo target = newStage(tid, "下游", source.getId());
        TStageVo other = newStage(tid, "另一去向", target.getId());

        // 下游赛段当前只有一条建段自动补的默认衔接(source → target)
        List<TStageRosterGroupBo> groups = rosterService.groupsOfStage(target.getId());
        assertEquals(1, groups.size(), "新赛段应只有一条默认衔接");
        Long generated = groups.get(0).getId();

        // 先加一条非默认出口:source → 另一去向
        addRankGroup(tid, other.getId(), source.getId(), 1, 1);

        // 再删默认出口:下游赛段变成"零来源",应被允许(删掉就是删掉)
        rosterService.removeGroup(target.getId(), generated);
        assertTrue(rosterService.groupsOfStage(target.getId()).isEmpty(),
            "删掉唯一来源后,目标赛段应没有来源组");
        // 另一去向的人工出口不受影响
        assertTrue(rosterService.groupsOfStage(other.getId()).stream()
                .anyMatch(g -> Integer.valueOf(1).equals(g.getRankStart())),
            "另一去向的人工出口应保留");
    }

    // ===== 工具 =====

    /** 1 号座位上的人(取人顺序直接决定谁先落座) */
    private Long firstSeatSourceId(Long targetStageId) {
        return rosterService.entriesOf(targetStageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> Long.valueOf(1L).equals(e.getSlot()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private Long addRankGroup(Long tid, Long targetStageId, Long sourceStageId, int start, int end) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode("AUTO");
        g.setQuota(0);
        g.setRankStart(start);
        g.setRankEnd(end);
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(sourceStageId);
        bo.setGroups(List.of(g));
        rosterService.addGroups(targetStageId, bo);
        return rosterService.groupsOfStage(targetStageId).stream()
            .filter(x -> Integer.valueOf(start).equals(x.getRankStart()))
            .map(TStageRosterGroupBo::getId).findFirst()
            .orElseThrow(() -> new AssertionError("出口未写入"));
    }

    private Long insertCompetitor(Long tid, Long stageId, String name, String number,
                                  Long finalRank, String outcome) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber(number);
        c.setSeedRank(finalRank);
        c.setFinalRank(finalRank);
        c.setOutcomeStatus(outcome);
        competitorMapper.insert(c);
        return c.getId();
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, Long afterStageId) {
        return newStage(tournamentId, name, afterStageId, "KNOCKOUT", "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":8,"
            + "\"advanceCount\":4,\"pairingMode\":\"SEQUENTIAL\"}}");
    }

    private TStageVo newAuditionStage(Long tournamentId, String name, Long afterStageId) {
        return newStage(tournamentId, name, afterStageId, "AUDITION",
            "{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":2,\"circleAdvanceCounts\":[2]}");
    }

    private TStageVo newStage(Long tournamentId, String name, Long afterStageId,
                              String stageMode, String ruleConfig) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(stageMode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(8L);
        bo.setTeamCountEnd(4L);
        bo.setAfterStageId(afterStageId);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(ruleConfig);
        return stageService.insertByBo(bo);
    }
}
