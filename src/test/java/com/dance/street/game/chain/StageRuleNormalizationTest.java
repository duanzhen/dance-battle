package com.dance.street.game.chain;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
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

/**
 * 赛段配置归一化:淘汰赛的规模/晋级数嵌在 {@code rule_config.knockout} 里。
 * 客户端若只传了 rule_config、没传权威字段(teamCountStart/End),后端要用配置反向回填,
 * 否则会落出「配置写了 16/8、字段是 0」——中间态据此显示"没有设置参赛人数"。
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class StageRuleNormalizationTest {

    private static final String DB_PATH = "target/stage-rule-normalization.db";

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
    @Autowired private ITStageService stageService;

    @Test
    void knockoutConfigFillsStageTeamCountsWhenClientOmitsThem() {
        Long tid = newTournament("归一化赛事");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("复活赛");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);   // 客户端漏传 → 0
        bo.setTeamCountEnd(0L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":16,\"advanceCount\":8,"
            + "\"pairingMode\":\"SEED\"}}");

        TStageVo vo = stageService.insertByBo(bo);
        TStage row = stageMapper.selectById(vo.getId());
        assertEquals(16L, row.getTeamCountStart(), "应从 rule_config.knockout.teamsCount 回填参赛人数");
        assertEquals(8L, row.getTeamCountEnd(), "应从 rule_config.knockout.advanceCount 回填晋级人数");
    }

    @Test
    void explicitStageTeamCountsAreKept() {
        Long tid = newTournament("归一化保持不变");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("32强");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(32L);
        bo.setTeamCountEnd(16L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\",\"knockout\":{\"teamsCount\":32,\"advanceCount\":16}}");

        TStageVo vo = stageService.insertByBo(bo);
        TStage row = stageMapper.selectById(vo.getId());
        assertEquals(32L, row.getTeamCountStart());
        assertEquals(16L, row.getTeamCountEnd());
    }

    @Test
    void arenaConfigFillsStageTeamCounts() {
        Long tid = newTournament("擂台归一化");
        TStageVo vo = insertStage(tid, "擂台", "ARENA", 0L, 0L, "{\"mode\":\"ARENA\",\"scale\":8}");
        TStage row = stageMapper.selectById(vo.getId());
        assertEquals(8L, row.getTeamCountStart());
        assertEquals(1L, row.getTeamCountEnd(), "擂台赛最终决出 1 个胜者");
    }

    @Test
    void rankConfigFillsStageTeamCounts() {
        Long tid = newTournament("排名归一化");
        TStageVo vo = insertStage(tid, "排名", "RANK", 0L, 0L,
            "{\"mode\":\"RANK\",\"scale\":32,\"advanceCount\":16}");
        TStage row = stageMapper.selectById(vo.getId());
        assertEquals(32L, row.getTeamCountStart());
        assertEquals(16L, row.getTeamCountEnd());
    }

    private TStageVo insertStage(Long tid, String name, String mode, Long start, Long end, String ruleConfig) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setIsInitialized(0L);
        bo.setRuleConfig(ruleConfig);
        return stageService.insertByBo(bo);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }
}
