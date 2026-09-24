package com.dance.street.game.controller;

import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MC 导播台的擂台总览接口走 <b>导播 authKey</b> 认证,而不是管理端 JWT。
 *
 * <p>回归:手机导播台此前借用管理端 {@code GET /game/stage/{id}/arena-overview}
 * ({@code @SaCheckPermission})读擂台队列。手机端只带导播 authKey、没有管理员 JWT,
 * 该请求返回 401,管理端响应拦截器见到 401 就弹「登录状态已过期」,点「临时弃权」时
 * 页面被弹窗挡住。这里验证新接口的鉴权与赛事归属校验。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class DirectorArenaOverviewTest {

    private static final String DB_PATH = "target/director-arena-overview.db";

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
    private ITStageService stageService;

    @Test
    void directorAuthKeyCanReadArenaOverview() throws Exception {
        Long tid = newTournament("擂台总览-甲", "key-arena-a");
        TStageVo stage = newArenaStage(tid, "擂台赛");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mvc.perform(get("/game/director/stage/" + stage.getId() + "/arena-overview")
                .header("Authorization", "Bearer key-arena-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.stageName").value("擂台赛"));
    }

    @Test
    void arenaOverviewRejectsMissingOrInvalidKey() throws Exception {
        Long tid = newTournament("擂台总览-乙", "key-arena-b");
        TStageVo stage = newArenaStage(tid, "擂台赛");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        // 无凭证:导播拦截器直接 401(不弹管理端登录框的前提是页面侧不再走管理员 JWT)
        mvc.perform(get("/game/director/stage/" + stage.getId() + "/arena-overview"))
            .andExpect(status().isUnauthorized());
        // 乱填凭证:同样 401
        mvc.perform(get("/game/director/stage/" + stage.getId() + "/arena-overview")
                .header("Authorization", "Bearer not-a-real-key"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void arenaOverviewRejectsStageOfAnotherTournament() throws Exception {
        Long other = newTournament("擂台总览-丙", "key-arena-c");
        TStageVo otherStage = newArenaStage(other, "别人的擂台");
        Long mine = newTournament("擂台总览-丁", "key-arena-d");
        newArenaStage(mine, "我的擂台");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        // 用甲的凭证读乙的赛段:必须被赛事归属校验拦住
        mvc.perform(get("/game/director/stage/" + otherStage.getId() + "/arena-overview")
                .header("Authorization", "Bearer key-arena-d"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value("赛段不存在或不属于当前赛事"));
    }

    // ===== 造数据 =====

    private Long newTournament(String name, String authKey) {
        TTournament t = new TTournament();
        t.setName(name);
        t.setAuthKey(authKey);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private TStageVo newArenaStage(Long tid, String name) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode("ARENA");
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"ARENA\",\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageService.insertByBo(bo);
    }
}
