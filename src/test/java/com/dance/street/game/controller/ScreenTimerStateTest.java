package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.ITVisWidgetService;
import org.dromara.common.core.exception.ServiceException;
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

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 倒计时开始/暂停的写入面:大屏只读,写入走管理端。
 *
 * <p>大屏投射端是公开播放端,只能读取;现场的「开始/暂停」由管理端发起。
 * 这里验证:公开的 {@code /tournament/screen/widget/{id}/timer-state} 已不存在(404),
 * 管理端 {@code /game/visWidget/{id}/timer-state} 带编辑权限注解,且写入只认
 * endAt/remainMs 两个键(计划时长不受影响、锁定控件也能计时、非倒计时组件拒绝)。</p>
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
    private TTournamentMapper tournamentMapper;
    @Autowired
    private ITVisWidgetService visWidgetService;

    /** 公开大屏不再有写接口:老路由不再映射,且不会落库 */
    @Test
    void publicScreenTimerWriteEndpointIsRemoved() throws Exception {
        Long id = newWidget("TIMER", "{\"title\":\"倒计时\",\"minutes\":5}", 0L);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mvc.perform(post("/tournament/screen/widget/" + id + "/timer-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endAt\":4102444800000,\"remainMs\":null}"));

        assertFalse(widgetMapper.selectById(id).getDataConfig().contains("endAt"),
            "公开路由已移除,配置不该被写入");

        boolean hasPublicMapping = Arrays.stream(ScreenController.class.getMethods())
            .anyMatch(m -> {
                org.springframework.web.bind.annotation.PostMapping pm =
                    m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
                return pm != null && Arrays.asList(pm.value()).contains("/widget/{id}/timer-state");
            });
        assertFalse(hasPublicMapping, "公开大屏控制器不该再有计时器写接口");
    }

    /** 管理端计时器接口必须受编辑权限保护 */
    @Test
    void adminTimerEndpointRequiresEditPermission() throws Exception {
        Method method = TVisWidgetController.class.getMethod("updateTimerState", Long.class, Map.class);
        SaCheckPermission ann = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(ann, "管理端计时器接口必须有 @SaCheckPermission");
        assertTrue(Arrays.asList(ann.value()).contains("game:visWidget:edit"),
            "应要求控件编辑权限,实际: " + Arrays.toString(ann.value()));
    }

    /** 开始/暂停只改 endAt/remainMs,计划时长(hours/minutes/...)不受影响 */
    @Test
    void timerStatePatchOnlyTouchesEndAtAndRemainMs() {
        Long id = newWidget("TIMER", "{\"title\":\"倒计时\",\"hours\":0,\"minutes\":5,"
            + "\"seconds\":0,\"milliseconds\":0}", 0L);

        visWidgetService.updateTimerState(id, Map.of("endAt", 4102444800000L));
        String started = widgetMapper.selectById(id).getDataConfig();
        assertTrue(started.contains("\"endAt\":4102444800000"), "应写入结束时间,实际: " + started);
        assertTrue(started.contains("\"hours\":0") && started.contains("\"minutes\":5"),
            "计划时长不能被改动,实际: " + started);

        Map<String, Object> pause = new HashMap<>();
        pause.put("endAt", null);
        pause.put("remainMs", 125000L);
        visWidgetService.updateTimerState(id, pause);

        String paused = widgetMapper.selectById(id).getDataConfig();
        assertTrue(paused.contains("\"endAt\":null"), "暂停要清掉结束时间,实际: " + paused);
        assertTrue(paused.contains("\"remainMs\":125000"), "暂停要把剩余时长写进配置,实际: " + paused);
        assertTrue(paused.contains("\"minutes\":5"), "暂停同样不能改计划时长,实际: " + paused);
    }

    /** 控件锁定是防误拖布局,现场开始/暂停仍要能落库(有意绕过锁定保护) */
    @Test
    void lockedTimerCanStillBeControlled() {
        Long id = newWidget("TIMER", "{\"minutes\":5}", 1L);
        visWidgetService.updateTimerState(id, Map.of("endAt", 4102444800000L));
        assertTrue(widgetMapper.selectById(id).getDataConfig().contains("\"endAt\":4102444800000"),
            "锁定的倒计时也应能开始/暂停");
    }

    @Test
    void nonTimerWidgetIsRejected() {
        Long id = newWidget("TEXT", "{\"text\":\"标题\",\"fontSize\":24}", 0L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> visWidgetService.updateTimerState(id, Map.of("endAt", 4102444800000L)));
        assertTrue(ex.getMessage().contains("仅倒计时组件"), "实际: " + ex.getMessage());
        assertFalse(widgetMapper.selectById(id).getDataConfig().contains("endAt"),
            "非倒计时组件的配置不该被写入");
    }

    @Test
    void unknownWidgetIsRejected() {
        assertThrows(ServiceException.class,
            () -> visWidgetService.updateTimerState(999999999L, Map.of("endAt", 1L)));
    }

    private Long newWidget(String type, String dataConfig, Long locked) {
        TTournament t = new TTournament();
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
        w.setLocked(locked);
        w.setLayoutConfig("{}");
        w.setDataConfig(dataConfig);
        w.setRenderConfig("{}");
        widgetMapper.insert(w);
        return w.getId();
    }
}
