package com.dance.street.game.service.impl;

import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import com.dance.street.game.service.impl.stage.ArenaRunService;
import com.dance.street.game.service.impl.stage.AuditionResultService;
import com.dance.street.game.service.impl.stage.ByeSettlementService;
import com.dance.street.game.service.impl.stage.FreeMatchService;
import com.dance.street.game.service.impl.stage.StageCheckInService;
import com.dance.street.game.service.impl.stage.StageRunService;
import com.dance.street.game.service.impl.stage.StageSettlementService;
import com.dance.street.game.service.impl.stage.StageSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 赛段生命周期编排实现(门面)。
 *
 * <p>这里只保留 {@link ITStageLifecycleService} 的公开契约与事务注解,方法体全部委托给
 * 按业务轴拆出的协作者。拆分的顺序与原因:</p>
 * <ul>
 *   <li>{@link StageSetupService} 初始化与生成对阵、{@link StageRunService} 开赛与赛中推进
 *       —— Run 依赖 Setup(startStage 会先初始化再生成对阵),所以 Setup 必须先拆;</li>
 *   <li>{@link StageSettlementService} 结算/撤销/晋级、{@link ByeSettlementService} 轮空结算;</li>
 *   <li>{@link ArenaRunService} 擂台轮转、{@link FreeMatchService} 自由对抗、
 *       {@link StageCheckInService} 签到落圈、{@link AuditionResultService} 海选结果导出;</li>
 *   <li>共享内核:{@link AuditionCircleSupport} 海选圈(口径 + 结构维护 + 圈级裁判绑定)。</li>
 * </ul>
 *
 * @author duane
 */
@RequiredArgsConstructor
@Service
public class TStageLifecycleServiceImpl implements ITStageLifecycleService {

    /** 海选圈口径、结构维护与圈级裁判绑定(圈只属于海选) */
    private final AuditionCircleSupport auditionCircleSupport;
    /** 海选结果口径与导出 */
    private final AuditionResultService auditionResultService;
    /** 擂台赛运行与总览 */
    private final ArenaRunService arenaRunService;
    /** 轮空场次结算(淘汰赛) */
    private final ByeSettlementService byeSettlementService;
    /** 赛段初始化与对阵生成 */
    private final StageSetupService stageSetupService;
    /** 开赛与赛中推进 */
    private final StageRunService stageRunService;
    /** 结算与晋级(完成赛段、撤销、晋级装配) */
    private final StageSettlementService stageSettlementService;
    /** 海选/排名赛签到落圈 */
    private final StageCheckInService stageCheckInService;
    /** 自由对抗(手动加场/删场/选晋级)的写操作 */
    private final FreeMatchService freeMatchService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initialize(InitializeStageBo bo) {
        stageSetupService.initialize(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMatches(GenerateMatchesBo bo) {
        stageSetupService.generateMatches(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureAuditionCircles(Long stageId) {
        auditionCircleSupport.ensureAuditionCircles(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startNextArenaMatch(Long stageId) {
        arenaRunService.startNextArenaMatch(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawArenaCompetitor(Long stageId, Long competitorId) {
        arenaRunService.withdrawArenaCompetitor(stageId, competitorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tempWithdrawArenaCompetitor(Long stageId, Long competitorId) {
        arenaRunService.tempWithdrawArenaCompetitor(stageId, competitorId);
    }

    /**
     * 轮空场次自动结算:单边轮空(1 名真人)直接判胜,按淘汰赛规则填下游占位或标记晋级;
     * 双边轮空(两个空位)无胜者,仅置为已结算。返回本次结算的场次数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settleByeMatches(Long stageId) {
        return byeSettlementService.settleByeMatches(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleByeMatch(Long matchId) {
        return byeSettlementService.settleByeMatch(matchId);
    }

    @Override
    public ArenaOverviewVo getArenaOverview(Long stageId) {
        return arenaRunService.getArenaOverview(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startStage(Long stageId) {
        stageRunService.startStage(stageId);
    }

    @Override
    public void assertRefereesEditable(Long stageId) {
        stageRunService.assertRefereesEditable(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId) {
        stageCheckInService.appendStageCompetitor(stageId, competitorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        stageCheckInService.appendStageCompetitor(stageId, competitorId, targetMatchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId, Integer zoneIndex) {
        stageCheckInService.appendStageCompetitor(stageId, competitorId, targetMatchId, zoneIndex);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void relocateCheckInCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        stageCheckInService.relocateCheckInCompetitor(stageId, competitorId, targetMatchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCheckInCompetitor(Long stageId, Long competitorId) {
        stageCheckInService.removeCheckInCompetitor(stageId, competitorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureStageGaming(Long stageId) {
        stageRunService.ensureStageGaming(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int setSeedOrder(SeedOrderBo bo) {
        return stageRunService.setSeedOrder(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StageCompleteVo completeStage(Long stageId) {
        return stageSettlementService.completeStage(stageId);
    }

    @Override
    public boolean isAutoConfirmAdvancement(Long tournamentId) {
        return stageSettlementService.isAutoConfirmAdvancement(tournamentId);
    }

    /**
     * 导出海选结果 Excel:
     * <ul>
     *   <li>"海选成绩" sheet:号码 / 选手名 / 各裁判分数 / 总分 / 排名,总分只统计原始海选场;</li>
     *   <li>二海/三海/… sheet:按加赛深度各占一张(号码 / 选手名 / 各裁判分数 / 总分 / 结果),
     *       加赛分数仅用于同分者决出晋级顺序,不进入主表总分。</li>
     * </ul>
     * 数据统一来自 {@link AuditionResultService#queryAuditionResult(Long)},此处不再重复聚合。
     */
    @Override
    public void exportAuditionResult(Long stageId, jakarta.servlet.http.HttpServletResponse response) {
        auditionResultService.exportAuditionResult(stageId, response);
    }

    /**
     * 查询海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
     * 二海分数只用于同分者决出晋级顺序,不计入原始总分;
     * 导出与前端各组件均消费本结果,不再各自聚合。
     */
    @Override
    public AuditionResultVo queryAuditionResult(Long stageId) {
        return auditionResultService.queryAuditionResult(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetStageToDraft(Long stageId) {
        stageSettlementService.resetStageToDraft(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int calculateAdvancement(Long stageId) {
        return stageSettlementService.calculateAdvancement(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmAdvancementOfPreviousStage(Long stageId) {
        return stageSettlementService.confirmAdvancementOfPreviousStage(stageId);
    }

    @Override
    public void setMatchCurrentCompetitor(Long matchId, Long competitorId) {
        stageRunService.setMatchCurrentCompetitor(matchId, competitorId);
    }

    @Override
    public Long getMatchCurrentCompetitor(Long matchId) {
        return stageRunService.getMatchCurrentCompetitor(matchId);
    }

    // ==================== 自由对抗(手动加场 + 手动晋级) ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFreeMatch(Long stageId, Long competitorAId, Long competitorBId) {
        return freeMatchService.createFreeMatch(stageId, competitorAId, competitorBId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFreeMatch(Long matchId) {
        freeMatchService.deleteFreeMatch(matchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int selectFreeMatchAdvancers(Long stageId, List<Long> competitorIds) {
        return freeMatchService.selectFreeMatchAdvancers(stageId, competitorIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustAdvancement(Long stageId, List<Long> competitorIds) {
        return stageSettlementService.adjustAdvancement(stageId, competitorIds);
    }

    @Override
    public RankDetailVo getRankDetail(Long stageId) {
        return stageSettlementService.getRankDetail(stageId);
    }

}
