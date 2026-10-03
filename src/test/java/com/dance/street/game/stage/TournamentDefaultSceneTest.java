package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.ITTournamentService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 建赛默认场景约定:
 * <ul>
 *   <li>非模版创建:自动补一个「主视觉」场景 + 全屏背景占位控件,画布取赛事设计稿尺寸,大屏建完即可用;</li>
 *   <li>模版创建:场景由模版自行创建,不能因为复用建赛入口而多插一个默认场景。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TournamentDefaultSceneTest {

    private static final String DB_PATH = "target/tournament-default-scene.db";

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

    @Autowired private ITTournamentService tournamentService;
    @Autowired private TVisSceneMapper visSceneMapper;
    @Autowired private TVisWidgetMapper visWidgetMapper;

    @Test
    void plainCreateAutoCreatesDefaultSceneWithBackground() {
        TTournamentBo bo = new TTournamentBo();
        bo.setName("手动建赛-默认场景");
        bo.setStatus(0L);
        bo.setLogicalWidth(1600L);
        bo.setLogicalHeight(900L);
        TTournamentVo tournament = tournamentService.insertByBo(bo);
        Long tid = tournament.getId();
        assertNotNull(tid);

        List<TVisScene> scenes = visSceneMapper.selectList(Wrappers.<TVisScene>lambdaQuery()
            .eq(TVisScene::getTournamentId, tid));
        assertEquals(1, scenes.size(), "非模版创建应恰好自动建出 1 个默认场景");
        TVisScene scene = scenes.get(0);
        assertEquals("主视觉", scene.getName());
        // 场景画布应跟随赛事设计稿尺寸,而不是写死 1920×1080
        assertEquals(1600L, scene.getDesignWidth(), "场景宽度应取赛事设计稿宽度");
        assertEquals(900L, scene.getDesignHeight(), "场景高度应取赛事设计稿高度");

        List<TVisWidget> widgets = visWidgetMapper.selectList(Wrappers.<TVisWidget>lambdaQuery()
            .eq(TVisWidget::getSceneId, scene.getId()));
        assertEquals(1, widgets.size(), "默认场景应带 1 个全屏背景占位控件");
        TVisWidget bg = widgets.get(0);
        assertEquals("IMAGE", bg.getType());
        assertEquals(0L, bg.getX());
        assertEquals(0L, bg.getY());
        assertEquals(1600L, bg.getW(), "背景控件应铺满场景宽度");
        assertEquals(900L, bg.getH(), "背景控件应铺满场景高度");
    }

    @Test
    void plainCreateWithoutLogicalSizeFallsBackToCanvasDefault() {
        TTournamentBo bo = new TTournamentBo();
        bo.setName("手动建赛-未填尺寸");
        bo.setStatus(0L);
        TTournamentVo tournament = tournamentService.insertByBo(bo);

        List<TVisScene> scenes = visSceneMapper.selectList(Wrappers.<TVisScene>lambdaQuery()
            .eq(TVisScene::getTournamentId, tournament.getId()));
        assertEquals(1, scenes.size());
        assertEquals(1920L, scenes.get(0).getDesignWidth());
        assertEquals(1080L, scenes.get(0).getDesignHeight());
    }

    @Test
    void templateCreateDoesNotAddExtraDefaultScene() {
        TTournamentTemplateBo bo = new TTournamentTemplateBo();
        bo.setName("模板建赛-不重复建场景");
        bo.setTemplateCode("AUDITION_16");
        TTournamentVo tournament = tournamentService.createByTemplate(bo);

        List<TVisScene> scenes = visSceneMapper.selectList(Wrappers.<TVisScene>lambdaQuery()
            .eq(TVisScene::getTournamentId, tournament.getId()));
        // AUDITION_16 模版自带「主视觉」「对战」两个场景,不应多出默认场景
        assertEquals(2, scenes.size(), "模版创建不应被插入额外的默认场景");
        long mainCount = scenes.stream().filter(s -> "主视觉".equals(s.getName())).count();
        assertEquals(1, mainCount, "「主视觉」场景应只有一个");
    }
}
