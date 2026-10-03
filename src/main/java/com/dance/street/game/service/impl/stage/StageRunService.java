package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import com.dance.street.game.service.impl.flow.MatchStateWriter;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.MatchCurrentCompetitorStore;
import com.dance.street.game.service.impl.stage.hook.StageHooks;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfile.GeneratePolicy;
import com.dance.street.game.engine.common.StageModeProfile.Setup.Trait;
import com.dance.street.game.engine.common.StageModeProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 赛段的开赛与赛中推进。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者。依赖方向是干净的
 * 单向:{@code Run → Setup/Arena} —— 开赛会先初始化、生成对阵(Setup),擂台赛则接着开第一场对决(Arena),
 * 而 Setup 与 Arena 都不反过来依赖这里,所以不会成环。</p>
 *
 * <p>搬动时注意:原来 {@code startStage} 调用的 initialize / generateMatches / startNextArenaMatch
 * 都是类内自调用,搬出后成为跨 bean 调用,依赖注入后语义不变(都加入同一事务)。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StageRunService {

    private final StageLookup stageLookup;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final ITStageRosterService rosterService;
    private final AuditionCircleSupport auditionCircleSupport;
    /** 开赛时先初始化并生成对阵 */
    private final StageSetupService stageSetupService;
    /** 擂台赛开赛后要立刻开第一场对决 */
    private final ArenaRunService arenaRunService;
    /** 场次状态推进唯一入口(场次 + 轮次成套写) */
    private final MatchStateWriter matchStateWriter;
    /** 当前上场选手标记的跨实例存储(Redis,单机降级为进程内) */
    private final MatchCurrentCompetitorStore currentCompetitorStore;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final StageHooks stageHooks;

    /** 开赛:一键完成初始化、生成对阵、开赛守卫与状态推进。 */
    @Transactional(rollbackFor = Exception.class)
    public void startStage(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        assertCanStart(stage);
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        // 一键开赛:无对阵时自动初始化(如未初始化)并生成对阵,淘汰赛/海选均适用
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        // 擂台赛/自由对抗不生成对阵(擂台由导播逐场创建,自由对抗对手线下抽签),其余赛制一键生成
        boolean generatesMatches = profile.setup().generatePolicy() == GeneratePolicy.GENERATE;
        if (exist == 0) {
            long entrants = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode()));
            if (entrants == 0) {
                // 本赛段没有参赛方(上一赛段晋级人数不足、甚至一个都没晋级):
                // 不初始化也不生成对阵,直接进入进行中,由导播台完成赛段即可。
                // 否则会卡在"赛段无可初始化的参赛方",后面的赛段整条链都走不下去。
                log.warn("赛段[{}]没有参赛方,跳过初始化与生成对阵,直接进入进行中", stageId);
            } else {
                if (!Long.valueOf(1L).equals(stage.getIsInitialized())) {
                    InitializeStageBo initBo = new InitializeStageBo();
                    initBo.setStageId(stageId);
                    stageSetupService.initialize(initBo);
                    // 初始化后重新读取赛段(状态/isInitialized 已更新)
                    stage = stageLookup.get(stageId);
                }
                if (generatesMatches) {
                    GenerateMatchesBo gm = new GenerateMatchesBo();
                    gm.setStageId(stageId);
                    stageSetupService.generateMatches(gm);
                }
            }
        }
        // 轮空场次不在此自动结算:保持 PENDING,由导播台逐场点「开始」时再自动结束(见 settleByeMatch),
        // 保证淘汰赛的每一场(含轮空)都经过导播台确认
        // 圈/对阵已预建(exist>0)时也要在开赛这一刻锁定名单:否则预建圈的赛段开赛后
        // isInitialized 仍是 0,种子位还能被继续调整。空赛段没有名单可锁,保持未初始化。
        if (exist > 0 && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            long pendingComps = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            if (pendingComps > 0) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stageId);
                stageSetupService.initialize(initBo);
                stage = stageLookup.get(stageId);
            }
        }
        // 海选落圈守卫:圈位由客户端在签到时指定,开赛前必须人人已落圈(见 assertAuditionAllAttached)
        // 生成后的赛制专属守卫(海选:人人已落圈、每圈有裁判)
        stageHooks.startGuard(stage.getStageMode())
            .ifPresent(guard -> guard.assertPostGenerate(stageLookup.get(stageId)));
        ensureStageGaming(stageId);

        // 擂台赛:开赛后自动创建并开始第一场对决(队首擂主 vs 队次挑战者)
        if (profile.run().autoStartFirstMatch()) {
            arenaRunService.startNextArenaMatch(stageId);
            return;
        }

        // 淘汰赛:开始赛段仅完成生成与开赛,场次全部保持待开始,由导播台逐场点「开始」开始(避免自动开始第一场)。
        // 海选等其他赛制:场次一并进入 GAMING,裁判可直接开评。
        boolean singleActive = profile.run().singleActiveMatch();
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
        for (int i = 0; i < matches.size(); i++) {
            // 轮空已自动结算的场次保持 SETTLED,不重置回 PENDING
            if (StageConstants.MATCH_SETTLED.equals(matches.get(i).getStatus())) {
                continue;
            }
            String targetStatus = singleActive
                ? StageConstants.MATCH_PENDING
                : StageConstants.MATCH_GAMING;
            // 场次与轮次成套推进
            matchStateWriter.setStatus(matches.get(i).getId(), targetStatus);
        }
    }

    /** 赛段一旦开始(非 DRAFT)就不能再改裁判配置。 */
    public void assertRefereesEditable(Long stageId) {
        if (stageId == null) {
            return;
        }
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            return;
        }
        throw new ServiceException("赛段[{}]已开始,裁判配置已锁定:如需调整请先重置赛段",
            stage.getName());
    }

    /** 幂等进入进行中:已是 GAMING/SETTLED 则空操作,不重复落库与广播。 */
    @Transactional(rollbackFor = Exception.class)
    public void ensureStageGaming(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            // 已是 GAMING/SETTLED:幂等空操作,不重复落库与广播
            return;
        }
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.updateById(upd);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    /** 按外部抽签结果设定种子顺序(仅未初始化的赛段可调)。 */
    @Transactional(rollbackFor = Exception.class)
    public int setSeedOrder(SeedOrderBo bo) {
        TStage stage = stageLookup.get(bo.getStageId());
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,种子已锁定;无法再按抽签结果调整顺序");
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法调整种子顺序");
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()));
        Set<Long> stageIds = comps.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        List<Long> order = bo.getCompetitorIds();
        if (order.size() != stageIds.size()) {
            throw new ServiceException("参赛方数量不一致:赛段共{}人,提交顺序{}人", stageIds.size(), order.size());
        }
        for (Long cid : order) {
            if (cid == null || !stageIds.contains(cid)) {
                throw new ServiceException("参赛方[{}]不属于当前赛段", cid);
            }
        }
        // 同上:抽签结果批量落库
        List<Map<String, Object>> seedItems = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", order.get(i));
            item.put("seedRank", (long) (i + 1));
            seedItems.add(item);
        }
        if (!seedItems.isEmpty()) {
            competitorMapper.batchUpdateSeedRank(seedItems);
        }
        log.info("赛段[{}]按外部抽签结果设定{}个参赛方种子顺序", stage.getId(), order.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
        return order.size();
    }

    /** 标记当前上场选手(跨实例缓存,不落库)。 */
    public void setMatchCurrentCompetitor(Long matchId, Long competitorId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        // 现场标记走跨实例缓存(Redis,单机降级为进程内),不落库——见 MatchCurrentCompetitorStore
        currentCompetitorStore.mark(matchId, competitorId);
        // 广播:大屏 widget 与导播台按事件刷新当前标记
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "stage");
    }

    public Long getMatchCurrentCompetitor(Long matchId) {
        return currentCompetitorStore.current(matchId);
    }

    /**
     * 开赛前的全部守卫:状态可开、海选圈已配置、上一赛段已结束、名单已就绪。
     * 任一不满足即抛 ServiceException;通过后调用方才有权生成对阵并置 GAMING。
     */
    private void assertCanStart(TStage stage) {
        // 未开始只有 DRAFT 一个状态:一键开始会自动初始化、生成对阵,再置 GAMING
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)状态的赛段可开始,当前: {}", stage.getStatus());
        }
        // 赛制专属开赛守卫(海选:必须先配置至少一圈(人数/裁判/去向))
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        if (profile.setup().has(Trait.REQUIRES_CIRCLES) && auditionCircleSupport.plannedCircleCount(stage) < 1) {
            throw new ServiceException("海选尚未配置圈,请先在赛段配置中新增至少一圈(人数/裁判/去向)");
        }
        // 开赛依赖 = 名单来源组里的<b>边</b>:每条边的来源赛段都必须已结束(SETTLED),
        // 且名单已确认/跳过。链(next_stage_id)只决定显示顺序,不参与开赛判定 ——
        // 所以多入口汇合 / 并行分支时,只要"自己那几条来源边"都跑完就能开,
        // 不会被"链上前一段还在跑"拦住(这正是多赛段同时进行的前提)。
        // 名单守卫(规则+覆盖+快照模型):语义见 ITStageRosterService#assertStageStartable
        rosterService.assertStageStartable(stage.getId());
    }

}
