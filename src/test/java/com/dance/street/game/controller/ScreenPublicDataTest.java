package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大屏公开数据接口:{@code /tournament/screen/**} 无需任何凭证即可读。
 *
 * <p>回归:投射页此前复用管理端 {@code /game/**} 的读取接口({@code @SaCheckPermission}),
 * 大屏机没有管理员 JWT 时全部 401,继而弹出管理端「登录状态已过期」把大屏挡住。
 * 这里验证大屏数据面已收敛到公开 controller,同时管理端同名接口仍然要求登录。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class ScreenPublicDataTest {

    private static final String DB_PATH = "target/screen-public-data.db";

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
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TRefereeMapper refereeMapper;
    @Autowired
    private ITStageService stageService;

    /**
     * 公开的大屏裁判列表不得带出裁判登录凭证 authKey(否则未认证访客可冒用裁判身份)。
     */
    @Test
    void screenRefereeListHidesAuthKey() throws Exception {
        Long tid = newTournament("大屏裁判脱敏");
        TReferee r = new TReferee();
        r.setTournamentId(tid);
        r.setName("裁判A");
        r.setAuthKey("SECRET-AUTH-KEY-XYZ");
        refereeMapper.insert(r);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        String body = mvc.perform(get("/tournament/screen/referee/list")
                .param("tournamentId", String.valueOf(tid)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("裁判A"))
            .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("SECRET-AUTH-KEY-XYZ"), "公开裁判列表泄露了 authKey: " + body);
    }

    /** 不带任何 Authorization 头也能读大屏数据 */
    @Test
    void screenDataIsReadableWithoutAnyCredential() throws Exception {
        Long tid = newTournament("大屏公开数据");
        TStageVo stage = newAuditionStage(tid, "海选");
        insertCompetitor(tid, stage.getId(), "选手1");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mvc.perform(get("/tournament/screen/tournament/" + tid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.name").value("大屏公开数据"));

        mvc.perform(get("/tournament/screen/stage/" + stage.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.stageMode").value("AUDITION"));

        mvc.perform(get("/tournament/screen/competitor/list").param("stageId", String.valueOf(stage.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get("/tournament/screen/match/list").param("stageId", String.valueOf(stage.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get("/tournament/screen/referee-stage/referee-ids").param("stageId", String.valueOf(stage.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 大屏独立控制器必须无认证且零写入:不允许出现任何 POST/PUT/PATCH/DELETE 接口。
     */
    @Test
    void screenControllerHasNoWriteEndpoints() {
        assertNotNull(ScreenController.class.getAnnotation(SaIgnore.class),
            "大屏控制器应整体 @SaIgnore(不认证)");
        boolean hasWrite = Arrays.stream(ScreenController.class.getDeclaredMethods())
            .anyMatch(m -> m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(PatchMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class));
        assertFalse(hasWrite, "大屏控制器不得有任何写入接口");
    }

    /**
     * 公开面只落在大屏 controller 内:管理端同名读取接口没有被顺手放开。
     *
     * <p>这里用结构化断言而不是发请求:Sa-Token 的注解鉴权依赖 {@code SaTokenContext}
     * (由 servlet 过滤器装配),MockMvc 不跑过滤器,命中受保护接口只会得到
     * 「SaTokenContext 上下文尚未初始化」的 500,测不出真实的 401/403 语义。</p>
     */
    @Test
    void adminEndpointsRemainProtected() throws Exception {
        assertNotNull(ScreenController.class.getAnnotation(SaIgnore.class),
            "大屏数据接口类应标注 @SaIgnore(公开只读)");

        Method sceneGetInfo = TVisSceneController.class.getMethod("getInfo", Long.class);
        assertNotNull(sceneGetInfo.getAnnotation(SaCheckPermission.class),
            "管理端场景详情仍应要求权限");
        assertNull(sceneGetInfo.getAnnotation(SaIgnore.class),
            "管理端场景详情不应被标为 @SaIgnore");

        Method stageGetInfo = TStageController.class.getMethod("getInfo", Long.class);
        assertNotNull(stageGetInfo.getAnnotation(SaCheckPermission.class),
            "管理端赛段详情仍应要求权限");
        assertNull(stageGetInfo.getAnnotation(SaIgnore.class),
            "管理端赛段详情不应被标为 @SaIgnore");
    }

    // ===== 造数据 =====

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newAuditionStage(Long tid, String name) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("AUDITION");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"AUDITION\",\"circles\":1,\"advanceCount\":1,\"maxScore\":100,"
            + "\"circleAdvanceCounts\":[1]}");
        return stageService.insertByBo(bo);
    }

    private void insertCompetitor(Long tid, Long stageId, String name) {
        TCompetitor c = new TCompetitor();
        c.setTournamentId(tid);
        c.setStageId(stageId);
        c.setType(0L);
        c.setName(name);
        c.setNumber("1");
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);
    }
}
