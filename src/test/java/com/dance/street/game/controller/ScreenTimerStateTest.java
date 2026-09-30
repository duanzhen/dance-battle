package com.dance.street.game.controller;

import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.mapper.TVisWidgetMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大屏倒计时状态回写接口。
 *
 * <p>大屏是公开播放端(不带管理员 JWT),现场的「开始/暂停」又必须落库——否则刷新页面后
 * 倒计时从头再来,和现场对不上表。这里验证:无凭证也能写、只写 endAt/remainMs、
 * 计划时长(hours/minutes/seconds/milliseconds)不受影响、非倒计时组件拒绝。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ScreenTimerStateTest {

    private static final String DB_PATH = "target/screen-timer-state.db";

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
    private WebApplicationContext wac;
    @Autowired
    private TVisWidgetMapper widgetMapper;
    @Autowired
    private com.dance.street.game.mapper.TTournamentMapper tournamentMapper;

    @Test
    void screenCanPersistTimerStateWithoutAuth() throws Exception {
        Long id = newWidget("TIMER", "{\"title\":\"倒计时\",\"hours\":0,\"minutes\":5,"
            + "\"seconds\":0,\"milliseconds\":0,\"fontSize\":48}");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        // 开始计时:写结束时间(大屏无 JWT 也必须能写)
        mvc.perform(post("/tournament/screen/widget/" + id + "/timer-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":4102444800000,\"remainMs\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String started = widgetMapper.selectById(id).getDataConfig();
        assertTrue(started.contains("\"endAt\":4102444800000"), "应写入结束时间,实际: " + started);
        assertTrue(started.contains("\"hours\":0") && started.contains("\"minutes\":5"),
            "计划时长不能被改动,实际: " + started);

        // 暂停:清结束时间,写剩余时长
        mvc.perform(post("/tournament/screen/widget/" + id + "/timer-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":null,\"remainMs\":125000}"))
            .andExpect(status().isOk());

        String paused = widgetMapper.selectById(id).getDataConfig();
        assertTrue(paused.contains("\"endAt\":null"), "暂停要清掉结束时间,实际: " + paused);
        assertTrue(paused.contains("\"remainMs\":125000"), "暂停要把剩余时长写进配置,实际: " + paused);
        assertTrue(paused.contains("\"minutes\":5"), "暂停同样不能改计划时长,实际: " + paused);
    }

    @Test
    void nonTimerWidgetIsRejected() throws Exception {
        Long id = newWidget("TEXT", "{\"text\":\"标题\",\"fontSize\":24}");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mvc.perform(post("/tournament/screen/widget/" + id + "/timer-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":4102444800000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value("仅倒计时组件支持回写计时状态"));

        assertFalse(widgetMapper.selectById(id).getDataConfig().contains("endAt"),
            "非倒计时组件的配置不该被写入");
    }

    @Test
    void unknownWidgetIsRejected() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(post("/tournament/screen/widget/999999999/timer-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msg").value("组件不存在"));
    }

    private Long newWidget(String type, String dataConfig) {
        com.dance.street.game.domain.TTournament t = new com.dance.street.game.domain.TTournament();
        t.setName(type + "测试赛事");
        tournamentMapper.insert(t);
        TVisWidget w = new TVisWidget();
        w.setTournamentId(t.getId());
        w.setSceneId(1000L);
        w.setType(type);
        w.setName(type + "测试");
        w.setX(0L);
        w.setY(0L);
        w.setW(300L);
        w.setH(100L);
        w.setZIndex(1L);
        w.setVisible(1L);
        w.setLocked(0L);
        w.setLayoutConfig("{}");
        w.setDataConfig(dataConfig);
        w.setRenderConfig("{}");
        widgetMapper.insert(w);
        assertEquals(type, widgetMapper.selectById(w.getId()).getType());
        return w.getId();
    }
}
