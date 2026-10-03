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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛段规模以权威字段(team_count_start/end)为准;保存时把这两个字段补进 rule_config,
 * 不做"从 rule_config 反向回填字段"——客户端必须自行传对字段,后端不再替旧数据兜底。
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
    void authoritativeCountsAreWrittenIntoRuleConfig() {
        Long tid = newTournament("字段写入配置");
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName("16强");
        bo.setStageMode("KNOCKOUT");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(16L);
        bo.setTeamCountEnd(8L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"KNOCKOUT\"}");

        TStageVo vo = stageService.insertByBo(bo);
        TStage row = stageMapper.selectById(vo.getId());
        assertTrue(row.getRuleConfig().contains("\"teamsCount\":16"), row.getRuleConfig());
        assertTrue(row.getRuleConfig().contains("\"advanceCount\":8"), row.getRuleConfig());
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }
}
