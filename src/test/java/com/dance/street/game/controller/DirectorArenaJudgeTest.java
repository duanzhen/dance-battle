package com.dance.street.game.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MC 导播台直接判定擂台赛(publishMode=DIRECTOR)。
 *
 * <p>擂台赛此前只有"裁判判罚"一条路:场次列表不带 publishMode、也没有配置入口,
 * 手机导播台的「左胜/平/右胜」按钮永远不出现。这里验证与淘汰赛同一套判定链路:
 * 场次列表带 DIRECTOR → 导播台提交胜负 → 场次结算 → 队列随之轮转,裁判端同时被拒。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class DirectorArenaJudgeTest {

    private static final String DB_PATH = "target/director-arena-judge.db";

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
    private TCompetitorMapper competitorMapper;
    @Autowired
    private TMatchMapper matchMapper;
    @Autowired
    private TMatchParticipantMapper participantMapper;
    @Autowired
    private ITStageService stageService;
    @Autowired
    private ITStageLifecycleService lifecycleService;
    @Autowired
    private ITMatchResultService matchResultService;

    /** 擂台赛配「导播台直接判定」:开赛 → 导播台判胜 → 开始下一场,队列按胜负轮转。 */
    @Test
    void directorJudgeSettlesArenaMatchAndRotatesQueue() throws Exception {
        Long tid = newTournament("擂台导播判罚", "key-arena-judge");
        TStageVo stage = newArenaStage(tid, "擂台赛");
        List<Long> ids = newCompetitors(tid, stage.getId(), 4);
        lifecycleService.startStage(stage.getId());

        TMatch first = gamingMatch(stage.getId());
        List<TMatchParticipant> parts = participantsOf(first.getId());
        Long defender = parts.get(0).getCompetitorId();
        Long challenger = parts.get(1).getCompetitorId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        // 1. 场次列表要带 publishMode,前端据此渲染「左胜/平/右胜」按钮
        // 雪花 ID 全局按字符串序列化(避免 JS 精度丢失),这里按字符串比对槽位
        mvc.perform(get("/game/director/match/list")
                .param("stageId", String.valueOf(stage.getId()))
                .header("Authorization", "Bearer key-arena-judge"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].publishMode").value("DIRECTOR"))
            .andExpect(jsonPath("$.data[0].status").value(StageConstants.MATCH_GAMING))
            .andExpect(jsonPath("$.data[0].leftCompetitorId").value(defender.toString()))
            .andExpect(jsonPath("$.data[0].rightCompetitorId").value(challenger.toString()));

        // 2. 导播台直接判定:挑战者胜
        String body = "{\"outcomes\":{\"" + defender + "\":\"LOSS\",\"" + challenger + "\":\"WIN\"}}";
        mvc.perform(post("/game/director/match/" + first.getId() + "/submit-result")
                .header("Authorization", "Bearer key-arena-judge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        assertEquals(StageConstants.MATCH_SETTLED, matchMapper.selectById(first.getId()).getStatus(),
            "导播判定后场次应结算");
        assertEquals("WIN", participantOf(first.getId(), challenger).getOutcomeStatus());
        assertEquals("LOSS", participantOf(first.getId(), defender).getOutcomeStatus());

        // 3. 判完点「开始下一场」:胜者守擂,败者队尾,由下一位挑战
        mvc.perform(post("/game/director/stage/" + stage.getId() + "/arena-next")
                .header("Authorization", "Bearer key-arena-judge"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        TMatch second = gamingMatch(stage.getId());
        List<TMatchParticipant> secondParts = participantsOf(second.getId());
        assertEquals(challenger, secondParts.get(0).getCompetitorId(), "胜者应守擂");
        assertEquals(ids.get(2), secondParts.get(1).getCompetitorId(), "败者排队尾,由下一位挑战");
    }

    /** 导播台判定模式下裁判不能再提交,避免同一场被两边各判一次。 */
    @Test
    void refereeCannotJudgeDirectorArenaMatch() {
        Long tid = newTournament("擂台判罚互斥", "key-arena-judge-2");
        TStageVo stage = newArenaStage(tid, "擂台赛");
        newCompetitors(tid, stage.getId(), 4);
        lifecycleService.startStage(stage.getId());

        TMatch first = gamingMatch(stage.getId());
        List<TMatchParticipant> parts = participantsOf(first.getId());
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(first.getId());
        bo.setRefereeId(1L);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(parts.get(0).getCompetitorId(), "WIN");
        outcomes.put(parts.get(1).getCompetitorId(), "LOSS");
        bo.setOutcomes(outcomes);

        ServiceException ex = assertThrows(ServiceException.class, () -> matchResultService.submitResult(bo));
        assertTrue(ex.getMessage().contains("导播台判定"), "应提示由导播台判定,实际:" + ex.getMessage());
        assertEquals(StageConstants.MATCH_GAMING, matchMapper.selectById(first.getId()).getStatus(),
            "裁判被拒后场次应保持进行中");
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
        bo.setTeamCountStart(4L);
        bo.setTeamCountEnd(1L);
        bo.setIsInitialized(0L);
        bo.setRuleConfig("{\"mode\":\"ARENA\",\"format\":\"BO1\",\"scale\":4,"
            + "\"drawBothScore\":false,\"publishMode\":\"DIRECTOR\","
            + "\"scoring\":{\"matchMode\":\"STANDARD\"}}");
        return stageService.insertByBo(bo);
    }

    private List<Long> newCompetitors(Long tid, Long stageId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            TCompetitor c = new TCompetitor();
            c.setTournamentId(tid);
            c.setStageId(stageId);
            c.setType(0L);
            c.setName("选手" + i);
            c.setNumber(String.valueOf(i));
            c.setSeedRank((long) i);
            c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
            competitorMapper.insert(c);
            ids.add(c.getId());
        }
        return ids;
    }

    private TMatch gamingMatch(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING))
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("没有进行中的场次"));
    }

    private List<TMatchParticipant> participantsOf(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private TMatchParticipant participantOf(Long matchId, Long competitorId) {
        return participantsOf(matchId).stream()
            .filter(p -> competitorId.equals(p.getCompetitorId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("场次里没有该选手"));
    }
}
