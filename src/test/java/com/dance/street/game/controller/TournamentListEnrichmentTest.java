package com.dance.street.game.controller;

import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端赛事大厅({@code GET /game/tournament/list})要下发展厅卡片需要的真实字段:
 * 创建时间、选手数、赛段名称列表(按赛段链顺序)。此前前端这些位置写死了占位值
 * (0/100、2024-01-01、积分赛),与真实数据不符,这里做回归保护。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TournamentListEnrichmentTest {

    private static final String DB_PATH = "target/tournament-list-enrichment.db";

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

    @Autowired private TTournamentController tournamentController;
    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TPlayerMapper playerMapper;
    @Autowired private TStageMapper stageMapper;

    @Test
    void listCarriesRealCreateTimeAndCounts() {
        TTournament tournament = new TTournament();
        tournament.setName("列表聚合校验赛");
        tournament.setStatus(0L);
        tournamentMapper.insert(tournament);
        Long tid = tournament.getId();
        assertNotNull(tid);

        for (String name : new String[]{"甲", "乙", "丙"}) {
            TPlayer p = new TPlayer();
            p.setTournamentId(tid);
            p.setName(name);
            playerMapper.insert(p);
        }

        // 先插"决赛",再插"海选"并让海选.next=决赛:id 顺序与链顺序相反,用来验证是按链排序而不是按 id
        TStage finalStage = new TStage();
        finalStage.setTournamentId(tid);
        finalStage.setName("决赛");
        finalStage.setStageMode("KNOCKOUT");
        stageMapper.insert(finalStage);

        TStage audition = new TStage();
        audition.setTournamentId(tid);
        audition.setName("海选");
        audition.setStageMode("AUDITION");
        audition.setNextStageId(finalStage.getId());
        stageMapper.insert(audition);

        // 作废(已丢弃)的赛段不应出现在卡片上
        TStage discarded = new TStage();
        discarded.setTournamentId(tid);
        discarded.setName("废弃赛段");
        discarded.setStageMode("KNOCKOUT");
        discarded.setStatus(StageConstants.STAGE_DISCARD);
        stageMapper.insert(discarded);

        TableDataInfo<TTournamentVo> page = tournamentController.list(new TTournamentBo(), new PageQuery(100, 1));
        assertNotNull(page.getData());

        TTournamentVo row = page.getData().stream()
            .filter(vo -> Objects.equals(vo.getId(), tid))
            .findFirst()
            .orElseThrow();

        assertEquals(3L, row.getPlayerCount(), "选手数应为真实聚合值");
        assertEquals(List.of("海选", "决赛"), row.getStageNames(),
            "赛段名称应为真实值,并按赛段链顺序排列(而非 id 顺序),作废赛段不展示");
        assertNotNull(row.getCreateTime(), "创建时间应随列表返回,供卡片展示真实日期");
        assertTrue(
            page.getData().stream().allMatch(vo -> vo.getPlayerCount() != null && vo.getStageNames() != null),
            "同页所有赛事都应带上聚合数据,避免前端拿到 null 后回退成占位符");
    }
}
