package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.CheckInEditBo;
import com.dance.street.game.domain.bo.ScoreEntryBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
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
import org.dromara.common.core.exception.ServiceException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手工建赛的全生命周期压力测试:不靠模板,一步步照前端真实请求把赛事搭出来,跑完整个赛程,
 * 过程中随机注入现场最常出现的特殊场景。
 *
 * <p><b>为什么不用模板。</b>{@code createByTemplate} 会把赛段链、圈配置、裁判、出口一次配好,
 * 掩盖了手工建赛里那些真正容易配错、且各有守卫的步骤。本用例改用与前端逐屏一致的真实入口:</p>
 *
 * <ul>
 *   <li>赛事列表→新建:{@code ITTournamentService.insertByBo}(带裁判姓名)</li>
 *   <li>赛段流程→添加赛段:{@code ITStageService.insertByBo}(afterStageId 声明挂在哪段之后)</li>
 *   <li>赛段配置面板→保存:{@code ITStageService.updateConfig} + {@code ensureAuditionCircles}
 *       (控制器 editConfig 里就是这么串的)</li>
 *   <li>出口配置面板:{@code ITStageRosterService.addGroups / removeGroup / listByTarget}</li>
 *   <li>选手页建档 + 签到页签到:{@code ITPlayerService.insertByBo / checkIn / editCheckIn}</li>
 *   <li>导播台:开赛 / 判罚 / 完成赛段 / 撤销数据 / 中间态加减人 / 撤销人工条目</li>
 * </ul>
 *
 * <p><b>多轮 + 随机特殊场景。</b>每轮换一套规模与圈数,场景是否出现由固定种子的 {@link Random}
 * 决定(第 1 轮强制全开以保证覆盖),细节(改谁的名、剔哪个人、重置哪一段)也由随机决定。
 * 跑完所有轮次后断言每类场景都至少被注入过一次 —— 否则"随机"可能变成随机跳过,测试会悄悄失去覆盖。</p>
 *
 * @author duane
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class FullLifecycleE2ETest {

    private static final String DB_PATH = "target/full-lifecycle.db";

    /** 场景命中计数:跑完所有轮次后逐类断言"真的出现过" */
    private final Map<String, Integer> scenarioHits = new LinkedHashMap<>();

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

    /**
     * 手动建赛的三轮,每轮规模/圈数不同:
     * <ul>
     *   <li>第 1 轮:单圈 16 人取 12 进 16 强签表 → 有轮空;全场景开启</li>
     *   <li>第 2 轮:双圈各 8 人各取 4 → 满签表无轮空;圈内晋级线制造同分 → 二海</li>
     *   <li>第 3 轮:双圈 6/6 人,名额 4/2(不等额分流)→ 8 强签表有轮空</li>
     * </ul>
     */
    @Test
    void manualTournamentSurvivesRandomSpecialScenariosAcrossRounds() {
        runRound(new RoundPlan(1, 1, 16, new int[]{12}, new int[]{16, 8, 4, 2}, false));
        runRound(new RoundPlan(2, 2, 8, new int[]{4, 4}, new int[]{8, 4, 2}, true));
        runRound(new RoundPlan(3, 2, 6, new int[]{4, 2}, new int[]{8, 4, 2}, false));

        // 随机不能变成随机跳过:每类特殊场景都要按最低次数真的被注入过(第 1 轮强制全开保底)
        assertAtLeast("多圈分流", 2);
        assertAtLeast("二海", 1);
        assertAtLeast("轮空", 1);
        assertAtLeast("改名", 6);        // 每轮:赛段改名 + 选手改名
        assertAtLeast("中间态增减", 1);
        assertAtLeast("撤销赛段重来", 1);
        assertAtLeast("错误操作守卫", 6);   // 每轮至少:未落圈签到 / 开赛后改签到 / 淘汰段两条守卫
    }

    private void assertAtLeast(String scenario, int minimum) {
        assertTrue(hits(scenario) >= minimum,
            "场景[" + scenario + "]只出现 " + hits(scenario) + " 次,少于预期 " + minimum + " 次,覆盖已失效:" + scenarioHits);
    }

    // ==================================================================
    // 一轮完整流程
    // ==================================================================

    /**
     * 一轮 = 建赛 → 加赛段 → 配赛段/圈 → 签到 → 开赛 → 判罚 → 逐段晋级 → 冠军。
     * 特殊场景按 {@code plan}/随机在过程中注入。
     */
    private void runRound(RoundPlan plan) {
        Random rng = new Random(20261002L + plan.no());
        // 第 1 轮强制全开保证覆盖;之后每轮由随机决定各类场景是否出现
        boolean withRosterEdit = plan.no() == 1 || rng.nextBoolean();
        boolean withReset = plan.no() == 1 || rng.nextBoolean();
        boolean withGuards = true;
        int players = plan.circles() * plan.playersPerCircle();
        int advance = sum(plan.quotas());

        // ---------- 1. 新建赛事(赛事列表→新建,带裁判名单) ----------
        TTournamentBo tbo = new TTournamentBo();
        tbo.setName("手动建赛-第" + plan.no() + "轮");
        tbo.setStatus(0L);
        tbo.setLogicalWidth(1920L);
        tbo.setLogicalHeight(1080L);
        List<String> refereeNames = new ArrayList<>();
        for (int i = 1; i <= plan.circles(); i++) {
            refereeNames.add("手动裁判" + plan.no() + "-" + i);
        }
        tbo.setRefereeNames(refereeNames);
        TTournamentVo tournament = tournamentService.insertByBo(tbo);
        Long tid = tournament.getId();
        List<Long> referees = refereeIdsOf(tid);
        assertEquals(plan.circles(), referees.size(), "每个圈各配 1 名裁判");

        // ---------- 2. 逐个添加赛段(赛段流程→添加赛段) ----------
        TStageVo audition = addStage(tid, "海选", "AUDITION", 0L, (long) advance, null, null);
        List<TStageVo> knockouts = new ArrayList<>();
        Long prev = audition.getId();
        for (int capacity : plan.chain()) {
            TStageVo stage = addStage(tid, capacity + "强", "KNOCKOUT",
                (long) capacity, (long) capacity / 2, prev, knockoutRule(capacity));
            knockouts.add(stage);
            prev = stage.getId();
        }

        // ---------- 3. 配置海选(配置面板保存 + 建圈) ----------
        saveAuditionConfig(tid, audition, plan, referees, advance);
        lifecycleService.ensureAuditionCircles(audition.getId());
        List<TMatch> circles = matchesOf(audition.getId());
        assertEquals(plan.circles(), circles.size(), "应按配置建出 " + plan.circles() + " 个圈");
        if (plan.circles() > 1) {
            hit("多圈分流");
        }

        // ---------- 4. 选手建档 + 签到(选手页 + 签到页) ----------
        List<TPlayerVo> checkedIn = checkInAll(tid, plan, circles, players, rng);

        // ---------- 5. 改名(签到页编辑,必须在开赛前) ----------
        renameOnePlayer(checkedIn, rng);

        // ---------- 6. 错误操作:没选圈就签到 → 守卫应拒绝且不留半成品 ----------
        if (withGuards) {
            assertGuardRejectsCheckInWithoutCircle(tid, plan);
        }

        // ---------- 7. 开赛 + 开赛后签到锁定守卫 ----------
        lifecycleService.startStage(audition.getId());
        assertEquals(StageConstants.STAGE_GAMING, statusOf(audition.getId()), "海选开赛后应进行中");
        if (withGuards) {
            assertGuardRejectsCheckInEditAfterStart(checkedIn.get(0));
            hit("错误操作守卫");
        }

        // ---------- 8. 打分(可能触发二海)→ 完成海选 ----------
        runAudition(plan, audition, circles, referees, rng, advance);

        // ---------- 9. 首段淘汰:出口按圈配、中间态增减、撤销重来 ----------
        TStageVo firstKo = knockouts.get(0);
        configurePerCircleExit(audition, firstKo, plan, advance);
        RosterEditOutcome edited = null;
        if (withRosterEdit && advance < firstKo.getTeamCountStart()) {
            edited = editMiddleState(firstKo, plan, rng);
        }
        assertEntrantsAfterApply(firstKo, advance, edited);
        judgeKnockout(firstKo, withGuards);
        int actualAdvance = (int) countOutcome(firstKo.getId(), OutcomeStatusEnum.ADVANCE.getCode());

        if (withReset) {
            resetAndReplay(firstKo, advance, edited);
            actualAdvance = (int) countOutcome(firstKo.getId(), OutcomeStatusEnum.ADVANCE.getCode());
        }

        // ---------- 10. 其余淘汰段:装配 → 开赛 → 判罚 → 完成 ----------
        int expectedAdvance = actualAdvance;
        for (int i = 1; i < knockouts.size(); i++) {
            TStageVo stage = knockouts.get(i);
            int entrants = expectedAdvance;
            rosterService.applyRoster(stage.getId(), null);
            assertEquals(entrants, competitorCount(stage.getId()),
                stage.getName() + " 应装配 " + entrants + " 人");
            if (i == 1 && withGuards && withReset) {
                // 下游已开赛:再撤销上一段必须被守卫拦下(撤销只能从后往前)
                lifecycleService.startStage(stage.getId());
                ServiceException ex = assertThrows(ServiceException.class,
                    () -> lifecycleService.resetStageToDraft(firstKo.getId()),
                    "下游已开赛时撤销上游应被拒绝");
                assertTrue(ex.getMessage().contains("下游赛段"), "实际:" + ex.getMessage());
                hit("错误操作守卫");
                judgeKnockoutStarted(stage);
            } else {
                judgeKnockout(stage, withGuards);
            }
            expectedAdvance = (int) countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode());
        }

        // ---------- 11. 收尾:全段结算 + 唯一冠军 ----------
        assertTournamentFinished(tid, plan, knockouts.size() + 1);
    }

    // ==================================================================
    // 建赛 / 配赛段
    // ==================================================================

    /** 添加赛段:afterStageId 声明挂在哪段之后(与前端「添加赛段」一致)。 */
    private TStageVo addStage(Long tid, String name, String mode, Long start, Long end,
                              Long afterStageId, String ruleConfig) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tid);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(start);
        bo.setTeamCountEnd(end);
        bo.setIsInitialized(0L);
        bo.setAfterStageId(afterStageId);
        // 先按最小配置建段,后续「配置面板→保存」再写完整规则(与手工操作顺序一致)
        bo.setRuleConfig("{\"mode\":\"" + mode + "\"}");
        return stageService.insertByBo(bo);
    }

    /** 保存海选配置:圈数、每圈名额、每圈裁判(配置面板保存后控制器会再同步圈结构)。 */
    private void saveAuditionConfig(Long tid, TStageVo audition, RoundPlan plan,
                                    List<Long> referees, int advance) {
        TStageConfigBo cfg = new TStageConfigBo();
        cfg.setId(audition.getId());
        cfg.setTournamentId(tid);
        // 改名场景:赛段改名同样走配置保存
        cfg.setName("海选-第" + plan.no() + "轮(已改名)");
        cfg.setStageMode("AUDITION");
        cfg.setTeamCountStart(0L);
        cfg.setTeamCountEnd((long) advance);
        cfg.setRuleConfig(auditionRule(plan, referees, advance));
        stageService.updateConfig(cfg);
        hit("改名");
    }

    private String auditionRule(RoundPlan plan, List<Long> referees, int advance) {
        StringBuilder quotas = new StringBuilder("[");
        StringBuilder refs = new StringBuilder("[");
        for (int i = 0; i < plan.circles(); i++) {
            quotas.append(i > 0 ? "," : "").append(plan.quotas()[i]);
            // 雪花 ID 用字符串写:写成 JSON 数字会在前端 JSON.parse 时被四舍五入
            refs.append(i > 0 ? "," : "").append("[\"").append(referees.get(i)).append("\"]");
        }
        quotas.append("]");
        refs.append("]");
        return "{\"mode\":\"AUDITION\",\"circles\":" + plan.circles()
            + ",\"maxScore\":10,\"advanceCount\":" + advance
            + ",\"circleAdvanceCounts\":" + quotas
            + ",\"circleRefereeIds\":" + refs
            + ",\"scoring\":{\"type\":\"TOTAL_SCORE\",\"matchMode\":\"VOTING\","
            + "\"aggregateRule\":\"SUM\",\"refereeAggregateRule\":\"SUM\"},"
            + "\"transition\":{}}";
    }

    private String knockoutRule(int capacity) {
        return "{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\","
            + "\"scoring\":{\"type\":\"WIN_LOSS_DRAW\",\"matchMode\":\"STANDARD\"},"
            + "\"knockout\":{\"teamsCount\":" + capacity + ",\"pairingMode\":\"SEED\","
            + "\"advanceCount\":" + (capacity / 2) + "},\"transition\":{}}";
    }

    /**
     * 出口配置:把首段淘汰的默认「上一段晋级者」出口换成「每圈第 1..名额名」——
     * 多圈分流的关键,不配的话名次是跨圈混排,圈与圈的人数就失去约束。
     */
    private void configurePerCircleExit(TStageVo audition, TStageVo target, RoundPlan plan, int advance) {
        TStageRosterBo bo = new TStageRosterBo();
        bo.setSourceStageId(audition.getId());
        bo.setResultFilter("ADVANCE");
        bo.setFillMode(RosterConstants.FILL_AUTO);
        bo.setQuota(0);
        List<TStageRosterGroupBo> groups = new ArrayList<>();
        for (int i = 0; i < plan.circles(); i++) {
            TStageRosterGroupBo g = new TStageRosterGroupBo();
            g.setSourceStageId(audition.getId());
            g.setResultFilter("ADVANCE");
            g.setZone(StageFlowSupport.circleZone(i + 1));
            g.setRankByZone(true);
            g.setRankStart(1);
            g.setRankEnd(plan.quotas()[i]);
            g.setFillMode(RosterConstants.FILL_AUTO);
            g.setQuota(0);
            groups.add(g);
        }
        bo.setGroups(groups);
        rosterService.addGroups(target.getId(), bo);
        // 移除默认的「全场名次」出口,避免与按圈出口重复取人
        Long generatedGroupId = null;
        for (TStageRosterVo roster : rosterService.listByTarget(target.getId())) {
            if (roster.getGroups() == null) {
                continue;
            }
            for (TStageRosterGroupBo g : roster.getGroups()) {
                if (audition.getId().equals(g.getSourceStageId())
                    && g.getZone() == null && g.getRankStart() == null && g.getRankEnd() == null) {
                    generatedGroupId = g.getId();
                    break;
                }
            }
        }
        if (generatedGroupId != null) {
            rosterService.removeGroup(target.getId(), generatedGroupId);
        }
        assertEquals(advance, rosterService.candidates(target.getId()).getGroups().stream()
                .mapToLong(g -> g.getCompetitors().size()).sum(),
            "按圈出口的候选数应等于各圈名额之和");
    }

    // ==================================================================
    // 签到 / 改名
    // ==================================================================

    /** 建档 + 签到:第 i 号 → 第 ((i-1) mod 圈数)+1 圈(与前端 circleIndexOfNumber 同一口径)。 */
    private List<TPlayerVo> checkInAll(Long tid, RoundPlan plan, List<TMatch> circles,
                                       int players, Random rng) {
        List<TPlayerVo> result = new ArrayList<>();
        // 报名顺序打乱:座位/对手随之变化,每轮不是同一张签表
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= players; i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, rng);

        for (int i = 1; i <= players; i++) {
            TPlayerBo pb = new TPlayerBo();
            pb.setTournamentId(tid);
            pb.setName("选手" + i);
            TPlayerVo player = playerService.insertByBo(pb);

            CheckInBo bo = new CheckInBo();
            bo.setPlayerId(player.getId());
            bo.setCheckInType("CREATE");
            bo.setCompetitorNumber(String.valueOf(i));
            bo.setMatchId(circles.get((i - 1) % plan.circles()).getId());
            TPlayerVo checkedInPlayer = playerService.checkIn(bo);
            assertNotNull(checkedInPlayer.getCompetitorId(), "选手" + i + " 签到后应绑定参赛单位");
            result.add(checkedInPlayer);
        }
        for (int c = 0; c < circles.size(); c++) {
            assertEquals(plan.playersPerCircle(), realParticipants(circles.get(c).getId()).size(),
                "第" + (c + 1) + "圈人数应等于分流人数");
        }
        return result;
    }

    /** 改名:签到页编辑选手姓名(仅开赛前可改)。 */
    private void renameOnePlayer(List<TPlayerVo> players, Random rng) {
        TPlayerVo target = players.get(rng.nextInt(players.size()));
        CheckInEditBo edit = new CheckInEditBo();
        edit.setPlayerId(target.getId());
        edit.setName("改名选手-" + target.getId());
        playerService.editCheckIn(edit);
        assertEquals("改名选手-" + target.getId(),
            competitorMapper.selectById(target.getCompetitorId()).getName(), "改名应写回参赛方");
        hit("改名");
    }

    // ==================================================================
    // 海选:打分 / 二海
    // ==================================================================

    /**
     * 海选打分并按需制造二海:被选中的圈在「晋级线」上让第 q、q+1 名同分,
     * 结算时会生成加赛场次;加赛判完再完成赛段。
     */
    private void runAudition(RoundPlan plan, TStageVo audition, List<TMatch> circles,
                             List<Long> referees, Random rng, int advance) {
        int tieCircle = plan.tiebreak() ? 1 + rng.nextInt(plan.circles()) : 0;
        for (int c = 0; c < circles.size(); c++) {
            submitAuditionScores(circles.get(c), referees.get(c), plan.quotas()[c],
                c + 1 == tieCircle);
        }
        StageCompleteVo done = lifecycleService.completeStage(audition.getId());

        int guard = 0;
        while (Boolean.TRUE.equals(done.getTiebreaker()) && guard++ < 4) {
            hit("二海");
            List<TMatch> pending = tiebreakerMatches(audition.getId()).stream()
                .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
                .toList();
            assertFalse(pending.isEmpty(), "返回「需要加赛」时必须已有加赛场次");
            for (TMatch tb : pending) {
                submitDistinctScores(tb, refereeOfZone(tb, circles, referees));
            }
            done = lifecycleService.completeStage(audition.getId());
        }
        assertTrue(Boolean.TRUE.equals(done.getCompleted()),
            "海选应正常结算完成,实际:" + done.getMessage());
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(audition.getId()), "海选应结束");
        assertEquals(advance, countOutcome(audition.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            "海选晋级人数应等于各圈名额之和");
    }

    /** 一个圈的打分;forceTie 时在晋级线上制造并列。分数随上场顺序递减,互不相同。 */
    private void submitAuditionScores(TMatch circle, Long refereeId, int quota, boolean forceTie) {
        List<TMatchParticipant> parts = realParticipants(circle.getId());
        List<ScoreEntryBo> scores = new ArrayList<>();
        BigDecimal boundary = BigDecimal.valueOf(100 - (quota - 1) * 5, 1);
        for (int i = 0; i < parts.size(); i++) {
            BigDecimal score;
            if (!forceTie) {
                score = BigDecimal.valueOf(100 - i * 5, 1);
            } else if (i < quota - 1) {
                score = BigDecimal.valueOf(100 - i * 5, 1);
            } else if (i == quota - 1 || i == quota) {
                score = boundary;
            } else {
                score = boundary.subtract(BigDecimal.valueOf((i - quota) * 5L, 1));
            }
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(score);
            scores.add(se);
        }
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(circle.getId());
        bo.setRefereeId(refereeId);
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /** 加赛场次按上场顺序给递减且互不相同的分,一次分出先后。 */
    private void submitDistinctScores(TMatch tiebreak, Long refereeId) {
        List<TMatchParticipant> parts = realParticipants(tiebreak.getId());
        List<ScoreEntryBo> scores = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ScoreEntryBo se = new ScoreEntryBo();
            se.setCompetitorId(parts.get(i).getCompetitorId());
            se.setScore(BigDecimal.valueOf(100 - i * 10, 1));
            scores.add(se);
        }
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(tiebreak.getId());
        bo.setRefereeId(refereeId);
        bo.setScores(scores);
        matchResultService.submitResult(bo);
    }

    /** 加赛场次属于哪个圈,就用该圈的裁判判。 */
    private Long refereeOfZone(TMatch tiebreak, List<TMatch> circles, List<Long> referees) {
        for (int i = 0; i < circles.size(); i++) {
            if (java.util.Objects.equals(circles.get(i).getDisplayZone(), tiebreak.getDisplayZone())) {
                return referees.get(i);
            }
        }
        return referees.get(0);
    }

    // ==================================================================
    // 中间态增减 / 撤销重来
    // ==================================================================

    /** 中间态人工调整的结果,用于装配后回验。 */
    private record RosterEditOutcome(String removedName, String guestName) {
    }

    /**
     * 中间态增减:先加一名外卡,再剔掉一名规则选中的人;装配后人数不变但成分应变化。
     *
     * <p>顺序不能反:外卡会占用「第一个空座位」,先剔除的话空出来的正是那个座位,
     * 外卡会直接坐进去并把移出标记行覆盖掉 —— 那是产品的合理行为,但不是本用例想验证的两笔独立调整。</p>
     */
    private RosterEditOutcome editMiddleState(TStageVo stage, RoundPlan plan, Random rng) {
        String guestName = "外卡-第" + plan.no() + "轮";
        TStageRosterOverrideBo add = new TStageRosterOverrideBo();
        add.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        add.setGuestName(guestName);
        rosterService.addOverride(stage.getId(), add);

        List<TStageRosterEntry> entries = rosterService.entriesOf(stage.getId()).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> !RosterConstants.ENTRY_ORIGIN_MANUAL.equals(e.getOrigin()))
            .toList();
        assertFalse(entries.isEmpty(), "应已有可调整的规则入选行");
        TStageRosterEntry victim = entries.get(rng.nextInt(entries.size()));
        String removedName = competitorMapper.selectById(victim.getSourceCompetitorId()).getName();

        TStageRosterOverrideBo remove = new TStageRosterOverrideBo();
        remove.setOp(RosterConstants.OVERRIDE_REMOVE);
        remove.setSourceCompetitorId(victim.getSourceCompetitorId());
        rosterService.addOverride(stage.getId(), remove);

        // 人工条目可见:外卡一条 + 移出座位一条
        assertEquals(2, rosterService.listOverrides(stage.getId()).size(), "两条人工调整都应出现在列表里");
        hit("中间态增减");
        return new RosterEditOutcome(removedName, guestName);
    }

    /** 装配后回验:人数守恒,被剔除的人不在、外卡在。 */
    private void assertEntrantsAfterApply(TStageVo stage, int advance, RosterEditOutcome edited) {
        rosterService.applyRoster(stage.getId(), null);
        List<TCompetitor> entered = competitorsOf(stage.getId());
        assertEquals(advance, entered.size(), "一减一加后人数应与名额一致");
        if (edited != null) {
            assertTrue(entered.stream().anyMatch(c -> edited.guestName().equals(c.getName())),
                "外卡应被物化进本段名单:" + names(entered));
            assertFalse(entered.stream().anyMatch(c -> edited.removedName().equals(c.getName())),
                "被剔除的人不应出现在本段名单:" + names(entered));
        }
    }

    /** 撤销赛段重来:重置 → 重新装配(人工调整保留)→ 重新判罚 → 再结算。 */
    private void resetAndReplay(TStageVo stage, int advance, RosterEditOutcome edited) {
        lifecycleService.resetStageToDraft(stage.getId());
        assertEquals(StageConstants.STAGE_DRAFT, statusOf(stage.getId()), "撤销后应回到规划中");
        assertEquals(0, competitorCount(stage.getId()), "撤销应清掉上一轮的快照名单");
        // 中间层人工调整是"重新确认的起点",不能丢
        if (edited != null) {
            assertTrue(rosterService.entriesOf(stage.getId()).stream()
                    .anyMatch(e -> edited.guestName().equals(e.getGuestName())),
                "撤销后中间态的人工调整应保留");
        }
        rosterService.applyRoster(stage.getId(), null);
        assertEquals(advance, competitorCount(stage.getId()), "重新装配人数应与名额一致");
        lifecycleService.startStage(stage.getId());
        judgeMatches(stage);
        lifecycleService.completeStage(stage.getId());
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(stage.getId()), "重来后应正常结束");
        hit("撤销赛段重来");
    }

    // ==================================================================
    // 淘汰赛段:开赛 / 判罚 / 完成
    // ==================================================================

    /** 完整跑一段淘汰:开赛(带未结算守卫校验)→ 判罚 → 完成 → 校验晋级人数。 */
    private void judgeKnockout(TStageVo stage, boolean withGuards) {
        lifecycleService.startStage(stage.getId());
        if (withGuards) {
            assertGuardsOnKnockoutStage(stage);
        }
        int expectedAdvancers = expectedAdvancers(stage);
        judgeMatches(stage);
        lifecycleService.completeStage(stage.getId());
        assertStageSettled(stage, expectedAdvancers);
    }

    /** 已开赛的淘汰段直接判罚(用于"下游已开赛"守卫校验之后)。 */
    private void judgeKnockoutStarted(TStageVo stage) {
        int expectedAdvancers = expectedAdvancers(stage);
        judgeMatches(stage);
        lifecycleService.completeStage(stage.getId());
        assertStageSettled(stage, expectedAdvancers);
    }

    private void judgeMatches(TStageVo stage) {
        for (TMatch match : matchesOf(stage.getId())) {
            List<TMatchParticipant> parts = realParticipants(match.getId());
            assertTrue(parts.size() <= 2, match.getName() + " 参赛方不应超过 2 人");
            matchResultService.startMatch(match.getId());
            if (parts.size() < 2) {
                // 轮空场:点开始即自动结算
                assertEquals(StageConstants.MATCH_SETTLED, statusOfMatch(match.getId()),
                    match.getName() + " 是轮空场,点开始应立刻自动结算");
                hit("轮空");
                continue;
            }
            finishMatch(match.getId(), parts.get(0).getCompetitorId(), parts.get(1).getCompetitorId());
        }
    }

    private void finishMatch(Long matchId, Long winnerId, Long loserId) {
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(matchId);
        Map<Long, String> outcomes = new HashMap<>();
        outcomes.put(winnerId, "WIN");
        outcomes.put(loserId, "LOSS");
        bo.setOutcomes(outcomes);
        matchResultService.submitResult(bo);
    }

    /**
     * 本段实际应晋级人数:每个"至少有一名参赛方"的场次恰好出一名晋级者,
     * 两边都空的场次不出人。有轮空时它小于签表容量的一半(例如 16 签表坐 12 人只出 8 人,
     * 剔掉一个正好和空位配对的人则只出 7 人)。
     */
    private int expectedAdvancers(TStageVo stage) {
        List<TMatch> matches = matchesOf(stage.getId());
        long empty = matches.stream().filter(m -> realParticipants(m.getId()).isEmpty()).count();
        return (int) (matches.size() - empty);
    }

    /** 结算校验:全部场次结算、晋级人数与"每个有人的场次出 1 人"一致、不超过签表计划。 */
    private void assertStageSettled(TStageVo stage, int expectedAdvancers) {
        assertEquals(StageConstants.STAGE_SETTLED, statusOf(stage.getId()),
            stage.getName() + " 应正常结束");
        assertEquals(0, matchesOf(stage.getId()).stream()
                .filter(m -> !StageConstants.MATCH_SETTLED.equals(m.getStatus())).count(),
            stage.getName() + " 结束后不应有未结算场次");
        assertEquals(stage.getTeamCountStart().intValue() / 2, matchesOf(stage.getId()).size(),
            stage.getName() + " 场次数应与签表容量匹配");
        assertEquals(expectedAdvancers,
            countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode()),
            stage.getName() + " 应晋级 " + expectedAdvancers + " 人");
        assertTrue(countOutcome(stage.getId(), OutcomeStatusEnum.ADVANCE.getCode())
                <= stage.getTeamCountEnd(),
            stage.getName() + " 晋级人数不应超过签表计划 " + stage.getTeamCountEnd());
    }

    // ==================================================================
    // 错误操作守卫
    // ==================================================================

    /** 没选圈就签到:后端必须拒绝,且事务回滚不留下"已建参赛单位但没落圈"的半成品。 */
    private void assertGuardRejectsCheckInWithoutCircle(Long tid, RoundPlan plan) {
        TPlayerBo pb = new TPlayerBo();
        pb.setTournamentId(tid);
        pb.setName("未选圈选手");
        TPlayerVo player = playerService.insertByBo(pb);

        CheckInBo bo = new CheckInBo();
        bo.setPlayerId(player.getId());
        bo.setCheckInType("CREATE");
        bo.setCompetitorNumber("999");
        // 多圈时既不传 matchId 也不传 zoneIndex:必须被拒绝
        ServiceException ex = assertThrows(ServiceException.class, () -> playerService.checkIn(bo));
        assertTrue(ex.getMessage().contains("请指定落圈"), "实际:" + ex.getMessage());
        assertNull(playerMapper.selectById(player.getId()).getCompetitorId(),
            "失败的签到必须整体回滚,不能留下半成品");
        hit("错误操作守卫");
    }

    /** 开赛后签到结果锁定:编辑/改号必须被拒绝。 */
    private void assertGuardRejectsCheckInEditAfterStart(TPlayerVo player) {
        CheckInEditBo edit = new CheckInEditBo();
        edit.setPlayerId(player.getId());
        edit.setName("开赛后改名");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> playerService.editCheckIn(edit));
        assertTrue(ex.getMessage().contains("签到结果已锁定"), "实际:" + ex.getMessage());
    }

    /** 淘汰段的两条守卫:未开赛的场次不能提交结果;场次没结算完不能直接把赛段标成已结束。 */
    private void assertGuardsOnKnockoutStage(TStageVo stage) {
        TMatch pending = matchesOf(stage.getId()).stream()
            .filter(m -> StageConstants.MATCH_PENDING.equals(m.getStatus()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("淘汰段开赛后应留有未开始的场次"));
        List<TMatchParticipant> parts = realParticipants(pending.getId());
        SubmitResultBo bo = new SubmitResultBo();
        bo.setMatchId(pending.getId());
        Map<Long, String> outcomes = new HashMap<>();
        Long a = parts.isEmpty() ? 1L : parts.get(0).getCompetitorId();
        outcomes.put(a, "WIN");
        bo.setOutcomes(outcomes);
        assertThrows(ServiceException.class, () -> matchResultService.submitResult(bo),
            "未开始的场次不能提交结果");

        TStageConfigBo bogus = new TStageConfigBo();
        bogus.setId(stage.getId());
        bogus.setStatus(StageConstants.STAGE_SETTLED);
        ServiceException ex = assertThrows(ServiceException.class, () -> stageService.updateConfig(bogus),
            "场次未结算完不能把赛段直接标成已结束");
        assertTrue(ex.getMessage().contains("不能直接标记为已结束"), "实际:" + ex.getMessage());
        hit("错误操作守卫");
    }

    // ==================================================================
    // 收尾
    // ==================================================================

    private void assertTournamentFinished(Long tid, RoundPlan plan, int expectedStages) {
        List<TStage> stages = stagesOf(tid);
        assertEquals(expectedStages, stages.size(), "赛段数应为 海选 + " + plan.chain().length + " 段淘汰");
        assertTrue(stages.stream().allMatch(s -> StageConstants.STAGE_SETTLED.equals(s.getStatus())),
            "所有赛段都应结算:" + stages.stream().map(s -> s.getName() + "=" + s.getStatus()).toList());
        TStage last = stages.get(stages.size() - 1);
        List<TCompetitor> champions = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, last.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        assertEquals(1, champions.size(), "最后一段应且仅应产出 1 名冠军:" + names(champions));
        assertEquals(Long.valueOf(1L), champions.get(0).getFinalRank(), "冠军名次应为 1");
        assertNotNull(champions.get(0).getName(), "冠军应有姓名(可能是改名后/外卡)");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void hit(String scenario) {
        scenarioHits.merge(scenario, 1, Integer::sum);
    }

    private int hits(String scenario) {
        return scenarioHits.getOrDefault(scenario, 0);
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    private String statusOf(Long stageId) {
        return stageMapper.selectById(stageId).getStatus();
    }

    private String statusOfMatch(Long matchId) {
        TMatch m = matchMapper.selectById(matchId);
        return m == null ? null : m.getStatus();
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

    private List<TCompetitor> competitorsOf(Long stageId) {
        return competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
    }

    private String names(List<TCompetitor> competitors) {
        return competitors.stream().map(TCompetitor::getName).toList().toString();
    }

    private List<TMatch> matchesOf(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatch> tiebreakerMatches(Long stageId) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getMatchType, StageConstants.MATCH_TYPE_TIEBREAKER)
            .orderByAsc(TMatch::getId));
    }

    private List<TMatchParticipant> realParticipants(Long matchId) {
        return participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
    }

    private List<TStage> stagesOf(Long tid) {
        TStageVo first = stageService.getFirstStageByTournamentId(tid);
        List<TStage> ordered = new ArrayList<>();
        Long id = first == null ? null : first.getId();
        while (id != null && ordered.size() < 20) {
            TStage s = stageMapper.selectById(id);
            if (s == null) {
                break;
            }
            ordered.add(s);
            id = s.getNextStageId();
        }
        return ordered;
    }

    private List<Long> refereeIdsOf(Long tid) {
        return refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
                .eq(TReferee::getTournamentId, tid)
                .orderByAsc(TReferee::getId))
            .stream().map(TReferee::getId).toList();
    }

    /**
     * 一轮的规模描述。
     *
     * @param no                轮次序号(1 起)
     * @param circles           海选圈数
     * @param playersPerCircle  每圈签到人数
     * @param quotas            每圈晋级名额
     * @param chain             淘汰链各段签表容量
     * @param tiebreak          是否制造晋级线同分(二海)
     */
    private record RoundPlan(int no, int circles, int playersPerCircle, int[] quotas,
                             int[] chain, boolean tiebreak) {
    }
}
