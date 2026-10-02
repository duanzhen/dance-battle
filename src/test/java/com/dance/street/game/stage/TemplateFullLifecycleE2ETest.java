package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITPlayerService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全生命周期回归:三种模板各从「一键建赛」跑到「冠军产生」。
 *
 * <p>与 {@link com.dance.street.game.stage.KnockoutTopByeChainE2ETest} 等既有整链测试的区别在于
 * <b>起点</b>:那些用例用 {@code tournamentMapper.insert} + 手工 {@code createStage} 拼赛段,
 * 绕过了真实的建赛入口与签到入口。本类全程只走产品真实路径:</p>
 *
 * <ol>
 *   <li>建赛:{@code ITTournamentService.createByTemplate}(模板建赛事 + 赛段链 + 场景/widget + 裁判 + 自动配圈)</li>
 *   <li>签到:{@code ITPlayerService.checkIn}(建参赛单位 + 成员关联 + 落圈)</li>
 *   <li>海选:开赛 → 圈上多裁判打分 → 完成赛段 → 按圈名次晋级</li>
 *   <li>淘汰:装配中间态名单 → 逐场开始/判罚 → 完成赛段 → 下一段</li>
 *   <li>擂台(仅擂台模板):开赛自动开首场 → 逐场轮转 → 完成赛段落冠军</li>
 * </ol>
 *
 * <p>三个模板覆盖的差异:AUDITION_32 为 海选→32→16→8→4→2;AUDITION_16 为 海选→16→8→4→2;
 * AUDITION_ARENA 为 海选→32→16→擂台赛(8→1),末段赛制完全不同。</p>
 *
 * @author duane
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TemplateFullLifecycleE2ETest {

    private static final String DB_PATH = "target/template-full-lifecycle.db";

    /** 模板名 → 该模板的赛段数与海选签到人数(依据 TEMPLATES 定义) */
    private static final int AUDITION_32_PLAYERS = 32;
    private static final int AUDITION_32_STAGES = 6;
    private static final int AUDITION_16_PLAYERS = 16;
    private static final int AUDITION_16_STAGES = 5;
    private static final int AUDITION_ARENA_PLAYERS = 32;
    private static final int AUDITION_ARENA_STAGES = 4;

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
    @Autowired private ITPlayerService playerService;
    @Autowired private ITStageService stageService;
    @Autowired private ITStageLifecycleService lifecycleService;
    @Autowired private ITStageRosterService rosterService;
    @Autowired private ITMatchResultService matchResultService;
    @Autowired private TStageMapper stageMapper;
    @Autowired private TCompetitorMapper competitorMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TMatchParticipantMapper participantMapper;
    @Autowired private TPlayerMapper playerMapper;
    @Autowired private TRefereeMapper refereeMapper;

    /** 32 模板:海选 → 32强 → 16强 → 8强 → 半决赛 → 决赛。 */
    @Test
    void audition32TemplateRunsFromCreationToChampion() {
        Long tid = runFullLifecycle("AUDITION_32", AUDITION_32_PLAYERS, AUDITION_32_STAGES);
        assertChampionOfLastStage(tid);
    }

    /** 16 模板:海选 → 16强 → 8强 → 半决赛 → 决赛。 */
    @Test
    void audition16TemplateRunsFromCreationToChampion() {
        Long tid = runFullLifecycle("AUDITION_16", AUDITION_16_PLAYERS, AUDITION_16_STAGES);
        assertChampionOfLastStage(tid);
    }

    /** 擂台模板:海选 → 32强 → 16强 → 擂台赛(8 人轮转 → 1 名冠军),末段赛制与另两个模板不同。 */
    @Test
    void auditionArenaTemplateRunsFromAuditionThroughArenaToChampion() {
        Long tid = runFullLifecycle("AUDITION_ARENA", AUDITION_ARENA_PLAYERS, AUDITION_ARENA_STAGES);
        assertChampionOfLastStage(tid);
    }

    // ==================================================================
    // 全流程主干
    // ==================================================================

    /**
     * 建赛 → 签到 → 海选 → 逐段推进(淘汰/擂台) → 最后一段结算。
     *
     * @return 赛事 ID
     */
    private Long runFullLifecycle(String templateCode, int players, int expectedStages) {
        Long tid = createByTemplate(templateCode);

        List<TStage> stages = stagesOf(tid);
        assertEquals(expectedStages, stages.size(),
            templateCode + " 应建出 " + expectedStages + " 个赛段,实际:" + namesOf(stages));
        assertEquals("AUDITION", stages.get(0).getStageMode(), "首个赛段必须是海选");
        assertTrue(linkedInOrder(stages), "赛段应按模板顺序串成 next 链:" + namesOf(stages));

        // 真实签到:选手建档在赛事下,签到建参赛单位并落进模板预建的圈
        checkInAll(tid, stages.get(0), players);
        assertEquals(players, competitorCount(stages.get(0).getId()),
            "签到后海选应有 " + players + " 名参赛方");

        runAuditionStage(stages.get(0), players);

        // 后续赛段:淘汰链一路推进;擂台模板的最后一段走擂台规则
        Long stageId = stageMapper.selectById(stages.get(0).getId()).getNextStageId();
        int guard = 0;
        while (stageId != null && guard++ < 10) {
            TStage stage = stageMapper.selectById(stageId);
            assertNotNull(stage, "next 链不应断");
            if ("ARENA".equals(stage.getStageMode())) {
                runArenaStage(stage);
            } else {
                runKnockoutStage(stage);
            }
            stageId = stageMapper.selectById(stageId).getNextStageId();
        }
        assertTrue(guard < 10, "赛段链疑似成环:" + namesOf(stagesOf(tid)));
        return tid;
    }

    /** 一键建赛;带两名裁判,模板会自动把他们绑到海选圈上(否则开赛守卫会拦住)。 */
    private Long createByTemplate(String templateCode) {
        TTournamentTemplateBo bo = new TTournamentTemplateBo();
        bo.setName("全流程-" + templateCode);
        bo.setTemplateCode(templateCode);
        bo.setRefereeNames(List.of("裁判A", "裁判B"));
        TTournamentVo vo = tournamentService.createByTemplate(bo);
        assertNotNull(vo, "按模板建赛应返回赛事");
        assertNotNull(vo.getId(), "赛事应有 ID");
        return vo.getId();
    }

    /** 逐个选手走真实签到入口:建参赛单位 + 成员关联 + 落进指定的圈。 */
    private void checkInAll(Long tid, TStage audition, int players) {
        List<TMatch> circles = matchesOf(audition.getId());
        assertEquals(1, circles.size(), "模板默认只建 1 个圈");
        Long circleId = circles.get(0).getId();
        for (int i = 1; i <= players; i++) {
            TPlayer player = new TPlayer();
            player.setTournamentId(tid);
            player.setName("选手" + i);
            playerMapper.insert(player);

            CheckInBo bo = new CheckInBo();
            bo.setPlayerId(player.getId());
            bo.setCheckInType("CREATE");
            bo.setCompetitorNumber(String.valueOf(i));
            bo.setMatchId(circleId);
            TPlayerVo checkedIn = playerService.checkIn(bo);
            assertNotNull(checkedIn.getCompetitorId(), "选手" + i + " 签到后应绑定参赛单位");

            assertEquals(circleId, participantMapper.selectOne(Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getCompetitorId, checkedIn.getCompetitorId()))
                .getMatchId(), "选手" + i + " 应落进模板预建的圈");
        }
    }

    /** 海选:开赛 → 每名裁判给全员打分 → 完成赛段 → 全员按圈名次晋级。 */
    private void runAuditionStage(TStage audition, int players) {
        Long stageId = audition.getId();
        List<Long> referees = refereeIdsOf(audition.getTournamentId());
        assertEquals(2, referees.size(), "模板按 refereeNames 建了 2 名裁判");

        lifecycleService.startStage(stageId);
        assertEquals(StageConstants.STAGE_GAMING, stageMapper.selectById(stageId).getStatus(),
            "海选开赛后应进入进行中");

        TMatch circle = matchesOf(stageId).get(0);
        List<TMatchParticipant> parts = realParticipants(circle.getId());
        assertEquals(players, parts.size(), "圈内应坐满签到选手");

        for (Long refereeId : referees) {
            SubmitResultBo bo = new SubmitResultBo();
            bo.setMatchId(circle.getId());
            bo.setRefereeId(refereeId);
            List<ScoreEntryBo> scores = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                ScoreEntryBo se = new ScoreEntryBo();
                se.setCompetitorId(parts.get(i).getCompetitorId());
                // 10.0 起每人递减 0.3:落在 0~maxScore(模板配 10)内且互不相同,名次确定
                se.setScore(BigDecimal.valueOf(100 - i * 3, 1));
                scores.add(se);
            }
            bo.setScores(scores);
            matchResultService.submitResult(bo);
        }

        lifecycleService.completeStage(stageId);
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stageId).getStatus(),
            "海选应正常结束");
        assertEquals(players, countOutcome(stageId, OutcomeStatusEnum.ADVANCE.getCode()),
            "海选前 " + players + " 名全部晋级");
        assertEquals(0, countOutcome(stageId, OutcomeStatusEnum.ELIMINATED.getCode()));
    }

    /** 淘汰赛一段:装配上一段晋级名单 → 开赛 → 逐场判胜负 → 完成赛段。 */
    private void runKnockoutStage(TStage stage) {
        Long stageId = stage.getId();
        int entrants = applyRosterIfNeeded(stageId);
        assertEquals(0, entrants % 2, "淘汰赛人数应为偶数(无轮空):" + entrants);

        lifecycleService.startStage(stageId);
        List<TMatch> matches = matchesOf(stageId);
        assertEquals(entrants / 2, matches.size(), stage.getName() + " 应有 " + (entrants / 2) + " 场对决");

        for (TMatch match : matches) {
            List<TMatchParticipant> parts = realParticipants(match.getId());
            assertEquals(2, parts.size(),
                stage.getName() + " 人数刚好填满签表,不应出现轮空场:" + match.getName());
            matchResultService.startMatch(match.getId());
            finishMatch(match.getId(), parts.get(0).getCompetitorId());
        }

        lifecycleService.completeStage(stageId);
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stageId).getStatus(),
            stage.getName() + " 应正常结束");
        assertEquals(0, matchesOf(stageId).stream()
            .filter(m -> !StageConstants.MATCH_SETTLED.equals(m.getStatus())).count(),
            stage.getName() + " 结束后不应有未结算场次");
        assertEquals(entrants / 2, countOutcome(stageId, OutcomeStatusEnum.ADVANCE.getCode()),
            stage.getName() + " 应有 " + (entrants / 2) + " 人晋级");
    }

    /** 擂台赛一段:开赛自动开首场 → 逐场轮转(n-1 场)→ 完成赛段落冠军与名次。 */
    private void runArenaStage(TStage stage) {
        Long stageId = stage.getId();
        int entrants = applyRosterIfNeeded(stageId);
        assertEquals(8, entrants, "擂台赛由 16 强晋级 8 人");

        lifecycleService.startStage(stageId);
        assertEquals(1, matchesOf(stageId).size(), "擂台赛开赛应自动创建第一场对决");
        assertEquals(StageConstants.MATCH_GAMING, matchesOf(stageId).get(0).getStatus(),
            "擂台赛场次创建即为进行中");

        // 每场让挑战者胜:胜者守擂、败者回队尾,队列整体前移;打满 n-1 场后人人上过场
        for (int i = 0; i < entrants - 1; i++) {
            TMatch gaming = matchesOf(stageId).stream()
                .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                .findFirst()
                .orElse(null);
            if (gaming == null) {
                lifecycleService.startNextArenaMatch(stageId);
                gaming = matchesOf(stageId).stream()
                    .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                    .findFirst()
                    .orElseThrow();
            }
            List<TMatchParticipant> parts = realParticipants(gaming.getId());
            assertEquals(2, parts.size(), "擂台赛每场都是 2 人对决");
            finishMatch(gaming.getId(), parts.get(1).getCompetitorId());
            assertEquals(entrants, lifecycleService.getArenaOverview(stageId).getQueue().size(),
                "每场之后队列都不应丢人");
        }

        lifecycleService.completeStage(stageId);
        assertEquals(StageConstants.STAGE_SETTLED, stageMapper.selectById(stageId).getStatus(),
            "擂台赛应正常结束");
        assertEquals(1, countOutcome(stageId, OutcomeStatusEnum.ADVANCE.getCode()),
            "擂台赛只产生 1 名冠军");
        assertEquals(entrants - 1, countOutcome(stageId, OutcomeStatusEnum.ELIMINATED.getCode()),
            "其余 " + (entrants - 1) + " 人应全部淘汰");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 给一场淘汰/擂台对决判胜负:winnerId 一方胜,另一方负。 */
    private void finishMatch(Long matchId, Long winnerId) {
        List<TMatchParticipant> parts = realParticipants(matchId);
        assertEquals(2, parts.size(), "对决应有 2 名参赛方");
        Long loser = parts.get(0).getCompetitorId().equals(winnerId)
            ? parts.get(1).getCompetitorId() : parts.get(0).getCompetitorId();
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(winnerId, "WIN");
        outcomes.put(loser, "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /** 本段名单尚未装配时按来源组装配,返回本段实际参赛方数量。 */
    private int applyRosterIfNeeded(Long stageId) {
        if (!Long.valueOf(1L).equals(stageMapper.selectById(stageId).getRosterApplied())) {
            rosterService.applyRoster(stageId, null);
        }
        int entrants = competitorCount(stageId);
        assertTrue(entrants > 0, "本段应有上一段晋级来的参赛方");
        return entrants;
    }

    /** 最后一段必须恰好产出 1 名冠军,且整个赛事所有赛段都已结束。 */
    private void assertChampionOfLastStage(Long tid) {
        List<TStage> stages = stagesOf(tid);
        assertTrue(stages.stream().allMatch(s -> StageConstants.STAGE_SETTLED.equals(s.getStatus())),
            "每个赛段都应正常结算,不能卡住:" + stages.stream()
                .map(s -> s.getName() + "=" + s.getStatus()).toList());
        TStage last = stages.get(stages.size() - 1);
        TCompetitor champion = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, last.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()))
            .stream().findFirst().orElse(null);
        assertNotNull(champion, "最后一段应产出 1 名冠军");
        assertEquals(Long.valueOf(1L), champion.getFinalRank(), "冠军名次应为 1");
        assertTrue(competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, last.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())) == 1,
            "冠军应且仅应有 1 名");
    }

    private List<TStage> stagesOf(Long tid) {
        List<TStage> stages = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, tid));
        // 按 next 链排序,保证是模板定义的执行顺序而不是 ID 顺序
        TStageVo first = stageService.getFirstStageByTournamentId(tid);
        List<TStage> ordered = new ArrayList<>();
        Long id = first == null ? null : first.getId();
        while (id != null && ordered.size() <= stages.size()) {
            TStage s = stageMapper.selectById(id);
            if (s == null) {
                break;
            }
            ordered.add(s);
            id = s.getNextStageId();
        }
        return ordered.isEmpty() ? stages : ordered;
    }

    private boolean linkedInOrder(List<TStage> stages) {
        for (int i = 0; i + 1 < stages.size(); i++) {
            if (!stages.get(i + 1).getId().equals(stages.get(i).getNextStageId())) {
                return false;
            }
        }
        return stages.get(stages.size() - 1).getNextStageId() == null;
    }

    private String namesOf(List<TStage> stages) {
        return stages.stream().map(s -> s.getName() + "(" + s.getStageMode() + ")").toList().toString();
    }

    private List<Long> refereeIdsOf(Long tid) {
        return refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
                .eq(TReferee::getTournamentId, tid)
                .orderByAsc(TReferee::getId))
            .stream().map(TReferee::getId).toList();
    }

    private int competitorCount(Long stageId) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)).intValue();
    }

    private long countOutcome(Long stageId, String outcome) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, outcome));
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> realParticipants(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }
}
