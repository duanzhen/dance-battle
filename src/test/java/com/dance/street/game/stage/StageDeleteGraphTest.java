package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITMatchService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除赛段必须同时处理名单依赖图和赛段拥有的场次数据。
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class StageDeleteGraphTest {

    private static final String DB_PATH = "target/stage-delete-graph.db";

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

    @Autowired private ITStageService stageService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private ITMatchService matchService;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TStageRosterEntryMapper entryMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;

    @Test
    void deletingMiddleStageClearsStalePeopleAndReprojectsSuccessor() {
        Long tid = newTournament("删除中间图节点");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TStageVo c = newStage(tid, "C", b.getId());

        setStatus(a.getId(), StageConstants.STAGE_GAMING);
        TCompetitor advancer = competitor(tid, a.getId(), "1", OutcomeStatusEnum.ADVANCE);

        TStageRosterGroupBo bc = rosterService.groupsOfStage(c.getId()).getFirst();
        TStageRosterEntry oldRow = new TStageRosterEntry();
        oldRow.setTournamentId(tid);
        oldRow.setTargetStageId(c.getId());
        oldRow.setSlot(1L);
        oldRow.setSlotKind(StageConstants.SLOT_BYE);
        oldRow.setSourceStageId(b.getId());
        oldRow.setSourceGroupId(bc.getId());
        oldRow.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
        oldRow.setStatus(RosterConstants.ENTRY_STATUS_READY);
        entryMapper.insert(oldRow);

        TStageRosterEntry deletedStageRow = new TStageRosterEntry();
        deletedStageRow.setTournamentId(tid);
        deletedStageRow.setTargetStageId(b.getId());
        deletedStageRow.setSlot(1L);
        deletedStageRow.setSlotKind(StageConstants.SLOT_BYE);
        deletedStageRow.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
        deletedStageRow.setStatus(RosterConstants.ENTRY_STATUS_READY);
        entryMapper.insert(deletedStageRow);

        assertTrue(stageService.deleteWithValidByIds(List.of(b.getId()), true));

        assertEquals(0, entryMapper.selectCount(Wrappers.<TStageRosterEntry>lambdaQuery()
                .eq(TStageRosterEntry::getTargetStageId, b.getId())),
            "被删赛段自己的中间态座位也应清理");
        List<TStageRosterGroupBo> groups = rosterService.groupsOfStage(c.getId());
        assertEquals(1, groups.size());
        assertEquals(a.getId(), groups.getFirst().getSourceStageId(), "B 的入边应改挂到 C");

        List<TStageRosterEntry> rows = rosterService.entriesOf(c.getId());
        assertTrue(rows.stream().noneMatch(row -> b.getId().equals(row.getSourceStageId())),
            "C 不应继续保留来自 B 的人员行");
        assertTrue(rows.stream().noneMatch(row -> bc.getId().equals(row.getSourceGroupId())),
            "C 不应继续引用已删除的 B→C 边");
        assertTrue(rows.stream().anyMatch(row -> a.getId().equals(row.getSourceStageId())
                && advancer.getId().equals(row.getSourceCompetitorId())),
            "C 应按新的 A→C 边重新投影出 A 的晋级者");
    }

    @Test
    void externalPromotionReferenceBlocksDelete() {
        Long tid = newTournament("跨赛段场次引用");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TMatch deletedTarget = newMatch(tid, b.getId(), "B场次");
        TMatch source = newMatch(tid, a.getId(), "A场次");

        PromotionTarget target = new PromotionTarget();
        target.setAction(StageConstants.ACTION_ADVANCE);
        target.setTargetMatchId(deletedTarget.getId());
        target.setTargetSlot(0);
        Map<String, PromotionTarget> rules = new LinkedHashMap<>();
        rules.put("1", target);
        source.setPromotionRule(RuleConfigParser.toJsonPromotionRule(rules));
        matchMapper.updateById(source);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> stageService.deleteWithValidByIds(List.of(b.getId()), true));
        assertTrue(ex.getMessage().contains("仍引用被删赛段场次"));
        assertNotNull(stageMapper.selectById(b.getId()));
    }

    @Test
    void deletingStageAlsoDeletesMatchRefereeRows() {
        Long tid = newTournament("删除场次裁判");
        TStageVo stage = newStage(tid, "A", null);
        TMatch match = newMatch(tid, stage.getId(), "A场次");
        TMatchReferee referee = new TMatchReferee();
        referee.setTournamentId(tid);
        referee.setMatchId(match.getId());
        referee.setRefereeId(999L);
        matchRefereeMapper.insert(referee);

        assertTrue(stageService.deleteWithValidByIds(List.of(stage.getId()), true));
        assertEquals(0, matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())));
        assertEquals(0, matchRefereeMapper.selectCount(Wrappers.<TMatchReferee>lambdaQuery()
            .eq(TMatchReferee::getMatchId, match.getId())));
    }

    @Test
    void stageWithCompetitorsCannotBeDeleted() {
        Long tid = newTournament("有参赛方不可删");
        TStageVo stage = newStage(tid, "A", null);
        competitor(tid, stage.getId(), "1", OutcomeStatusEnum.PENDING);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> stageService.deleteWithValidByIds(List.of(stage.getId()), true));
        assertTrue(ex.getMessage().contains("已有 1 个参赛方"));
        assertNotNull(stageMapper.selectById(stage.getId()));
    }

    @Test
    void stageWithPeopleInRosterMiddleStateCannotBeDeleted() {
        Long tid = newTournament("中间态有人不可删");
        TStageVo stage = newStage(tid, "A", null);
        TStageRosterEntry row = new TStageRosterEntry();
        row.setTournamentId(tid);
        row.setTargetStageId(stage.getId());
        row.setSlot(1L);
        row.setSlotKind(StageConstants.SLOT_PLAYER);
        row.setGuestName("待定选手");
        row.setOrigin(RosterConstants.ENTRY_ORIGIN_MANUAL);
        row.setStatus(RosterConstants.ENTRY_STATUS_PENDING);
        entryMapper.insert(row);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> stageService.deleteWithValidByIds(List.of(stage.getId()), true));
        assertTrue(ex.getMessage().contains("中间态名单已有 1 人"));
        assertNotNull(stageMapper.selectById(stage.getId()));
    }

    @Test
    void matchRoutingCannotPointToAnotherStage() {
        Long tid = newTournament("场次路由限制");
        TStageVo a = newStage(tid, "A", null);
        TStageVo b = newStage(tid, "B", a.getId());
        TMatch source = newMatch(tid, a.getId(), "A场次");
        TMatch foreign = newMatch(tid, b.getId(), "B场次");

        PromotionTarget target = new PromotionTarget();
        target.setAction(StageConstants.ACTION_ADVANCE);
        target.setTargetMatchId(foreign.getId());
        target.setTargetSlot(0);
        Map<String, PromotionTarget> rules = new LinkedHashMap<>();
        rules.put("1", target);

        TMatchBo update = new TMatchBo();
        update.setId(source.getId());
        update.setPromotionRule(RuleConfigParser.toJsonPromotionRule(rules));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> matchService.updateByBo(update));
        assertTrue(ex.getMessage().contains("必须是本赛段内的现存场次"));
    }

    private Long newTournament(String name) {
        TTournament tournament = new TTournament();
        tournament.setName(name);
        tournamentMapper.insert(tournament);
        return tournament.getId();
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

    private void setStatus(Long stageId, String status) {
        TStage update = new TStage();
        update.setId(stageId);
        update.setStatus(status);
        stageMapper.updateById(update);
    }

    private TCompetitor competitor(Long tournamentId, Long stageId, String number,
                                   OutcomeStatusEnum outcome) {
        TCompetitor competitor = new TCompetitor();
        competitor.setTournamentId(tournamentId);
        competitor.setStageId(stageId);
        competitor.setName("选手" + number);
        competitor.setNumber(number);
        competitor.setFinalRank(Long.valueOf(number));
        competitor.setOutcomeStatus(outcome.getCode());
        competitor.setType(0L);
        competitorMapper.insert(competitor);
        return competitor;
    }

    private TMatch newMatch(Long tournamentId, Long stageId, String name) {
        TMatch match = new TMatch();
        match.setTournamentId(tournamentId);
        match.setStageId(stageId);
        match.setName(name);
        match.setStatus(StageConstants.MATCH_PENDING);
        match.setMatchMode("STANDARD");
        match.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(match);
        return match;
    }
}
