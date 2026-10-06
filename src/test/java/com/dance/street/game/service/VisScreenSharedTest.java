package com.dance.street.game.service;

import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TVisScreenBo;
import com.dance.street.game.domain.vo.TVisScreenVo;
import com.dance.street.game.mapper.TTournamentMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 多控制端共用屏幕列表:屏幕按赛事持久化,当前投射的场景也落在屏幕上。
 *
 * <p>回归背景:屏幕原本存在各控制端浏览器的 history.state 里,多台控制端各有一份,
 * 互相看不到、也投射不到对方的屏幕。这里验证服务端共用模型:空赛事自动补默认屏、
 * 新增屏幕、记录当前投射场景、清除投射、删除屏幕。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class VisScreenSharedTest {

    private static final String DB_PATH = "target/vis-screen-shared.db";

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
    private ITVisScreenService visScreenService;
    @Autowired
    private TTournamentMapper tournamentMapper;

    @Test
    void screenListIsSharedPerTournamentAndKeepsCurrentScene() {
        Long tid = newTournament("共用屏幕赛事");

        // 空赛事自动补一块默认屏,保证任何控制端打开都有屏可用
        List<TVisScreenVo> initial = visScreenService.listByTournament(tid);
        assertEquals(1, initial.size(), "空赛事应自动补一块默认屏");
        assertEquals("屏幕 1", initial.get(0).getName());
        assertNull(initial.get(0).getCurrentSceneId(), "默认屏初始无投射");

        // 新增一块屏幕(服务端分配ID,多控制端共用)
        TVisScreenBo bo = new TVisScreenBo();
        bo.setTournamentId(tid);
        bo.setName("屏幕 2");
        TVisScreenVo added = visScreenService.insertByBo(bo);
        assertNotNull(added.getId());
        assertEquals(2, visScreenService.listByTournament(tid).size());

        // 记录/清除当前投射:新打开的控制端据此显示每块屏投的是什么
        visScreenService.bindCurrentScene(added.getId(), 123L);
        assertEquals(Long.valueOf(123L), currentSceneOf(tid, added.getId()));
        visScreenService.bindCurrentScene(added.getId(), null);
        assertNull(currentSceneOf(tid, added.getId()));

        // 删除屏幕后列表同步收敛
        visScreenService.deleteByIds(List.of(added.getId()));
        assertEquals(1, visScreenService.listByTournament(tid).size());
    }

    private Long currentSceneOf(Long tournamentId, Long screenId) {
        return visScreenService.listByTournament(tournamentId).stream()
            .filter(s -> s.getId().equals(screenId))
            .findFirst()
            .map(TVisScreenVo::getCurrentSceneId)
            .orElse(null);
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }
}
