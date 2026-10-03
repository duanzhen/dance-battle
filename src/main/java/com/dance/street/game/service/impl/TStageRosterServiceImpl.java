package com.dance.street.game.service.impl;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.impl.roster.RosterApplyService;
import com.dance.street.game.service.impl.roster.RosterEntryStore;
import com.dance.street.game.service.impl.roster.RosterGroupService;
import com.dance.street.game.service.impl.roster.RosterGroupStore;
import com.dance.street.game.service.impl.roster.RosterOverrideService;
import com.dance.street.game.service.impl.roster.RosterViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 名单服务实现(门面):一份数据三个层次——
 * <ul>
 *   <li>来源层:上游赛段的参赛方与成绩(只读);</li>
 *   <li>中间层:{@code t_stage_roster_entry} 一行 = 一个座位(有人/轮空都要占号),规则一变就整体重建,
 *       人工调整直接增删改这些行,读路径零计算;</li>
 *   <li>目标层:确认名单后物化出的 {@code t_competitor(stage=下一赛段, from_roster=1)}。</li>
 * </ul>
 * 规则存在独立边表 {@code t_stage_roster_group}(赛段间依赖的边+取人规则);
 * 状态位({@code roster_applied/roster_skipped})留在赛段行上。
 *
 * <p>这里只保留 {@link ITStageRosterService} 的公开契约与事务注解,方法体全部委托给按业务轴
 * 拆出的协作者:</p>
 * <ul>
 *   <li>{@link RosterGroupService} 出口(来源组)配置与规则、名单状态与开赛守卫;</li>
 *   <li>{@link RosterEntryStore} 中间层行的读写 + 整表重建 + 上游变更后的实时对账;</li>
 *   <li>{@link RosterOverrideService} 人工调整(加人/移出/钉座位/拖动);</li>
 *   <li>{@link RosterApplyService} 整单装配(确认名单,把中间层物化到目标层);</li>
 *   <li>{@link RosterViewService} 大屏参赛选手与中间态名单预览;</li>
 *   <li>更底层:{@link RosterGroupStore}(边表存储)、{@link RosterAssembler}(取人规则求值)、
 *       {@link RosterMaterializer}(逐行物化)。</li>
 * </ul>
 *
 * @author duane
 */
@RequiredArgsConstructor
@Service
public class TStageRosterServiceImpl implements ITStageRosterService {

    /** 来源组(出口)的存储:读、按 id 差异保存、变更广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 出口(来源组)配置与规则 */
    private final RosterGroupService rosterGroupService;
    /** 人工调整(中间层行上的加人/移出/钉座位/拖动) */
    private final RosterOverrideService rosterOverrideService;
    /** 对外视图:参赛选手(大屏)与中间态名单预览 */
    private final RosterViewService rosterViewService;
    /** 整单装配(确认名单):中间层整表物化到目标层 */
    private final RosterApplyService rosterApplyService;

    // ------------------------------------------------------------------
    // 名单来源组(赛段间依赖的边+取人规则)的读写
    //
    // 事实源 = t_stage_roster_group 表;t_stage 上只留 roster_applied/roster_skipped 两个状态位。
    // 取人顺序按 sort_order 升序,不再依赖 JSON 数组顺序。
    // ------------------------------------------------------------------

    @Override
    public List<TStageRosterGroupBo> groupsOfStage(Long targetStageId) {
        return rosterGroupStore.groupsOfStage(targetStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForStage(TStage stage) {
        rosterGroupService.ensureRosterForStage(stage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileAfterLinkChange(Long stageId) {
        rosterGroupService.reconcileAfterLinkChange(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForSurvivors(Collection<Long> tournamentIds) {
        rosterGroupService.ensureRosterForSurvivors(tournamentIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertStagesDeletable(Collection<Long> stageIds) {
        rosterGroupService.assertStagesDeletable(stageIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reattachEdgesOnStageDelete(Collection<Long> deletedStageIds, Map<Long, Long> successorByStage) {
        rosterGroupService.reattachEdgesOnStageDelete(deletedStageIds, successorByStage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeEntriesOfTargets(Collection<Long> targetStageIds) {
        rosterEntryStore.removeEntriesOfTargets(targetStageIds);
    }

    @Override
    public List<TStageRosterVo> listByTarget(Long targetStageId) {
        return rosterGroupService.listByTarget(targetStageId);
    }

    /**
     * 批量取名单(赛段列表/导播台列表用):整页 3 条 SQL。
     *
     * <p>逐个 listByTarget 时,"每赛段一次名单查询 + 每来源组一次来源赛段查询"会随赛段数放大;
     * 这里一次性取回赛段、中间层行与全部被引用的来源赛段状态,在内存组装。</p>
     */
    @Override
    public Map<Long, List<TStageRosterVo>> listByTargets(Collection<Long> targetStageIds) {
        return rosterGroupService.listByTargets(targetStageIds);
    }

    @Override
    public List<TStageRosterVo> listBySource(Long sourceStageId) {
        return rosterGroupService.listBySource(sourceStageId);
    }

    // ------------------------------------------------------------------
    // 人工调整(就是中间层里的行)
    // ------------------------------------------------------------------

    @Override
    public List<TStageRosterOverrideVo> listOverrides(Long stageId) {
        return rosterOverrideService.listOverrides(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageRosterOverrideVo addOverride(Long stageId, TStageRosterOverrideBo bo) {
        return rosterOverrideService.addOverride(stageId, bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOverride(Long stageId, Long overrideId, TStageRosterOverrideBo bo) {
        rosterOverrideService.updateOverride(stageId, overrideId, bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverride(Long stageId, Long overrideId) {
        rosterOverrideService.deleteOverride(stageId, overrideId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverrides(Long stageId, List<Long> overrideIds) {
        rosterOverrideService.deleteOverrides(stageId, overrideIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items) {
        rosterOverrideService.reorderRoster(stageId, items);
    }

    // ------------------------------------------------------------------
    // 来源组管理
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageRosterVo addGroups(Long stageId, TStageRosterBo bo) {
        return rosterGroupService.addGroups(stageId, bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeGroup(Long stageId, Long groupId) {
        rosterGroupService.removeGroup(stageId, groupId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long stageId, Long groupId, TStageRosterGroupBo group) {
        rosterGroupService.updateGroup(stageId, groupId, group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderGroups(Long stageId, List<Long> groupIds) {
        rosterGroupService.reorderGroups(stageId, groupIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSkipped(Long stageId) {
        rosterGroupService.markSkipped(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetByTarget(Long targetStageId) {
        rosterGroupService.resetByTarget(targetStageId);
    }

    // ------------------------------------------------------------------
    // 装配(唯一写库内核)
    // ------------------------------------------------------------------

    @Override
    public RosterCandidatesVo candidates(Long stageId) {
        return rosterGroupService.candidates(stageId);
    }

    @Override
    public boolean hasAnyCandidate(Long stageId) {
        return rosterGroupService.hasAnyCandidate(stageId);
    }

    @Override
    public boolean isRosterReady(Long stageId) {
        return rosterGroupService.isRosterReady(stageId);
    }

    @Override
    public void assertStageStartable(Long targetStageId) {
        rosterGroupService.assertStageStartable(targetStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections) {
        return rosterApplyService.applyRoster(targetStageId, manualSelections);
    }

    /**
     * 赛段参赛选手:名单已物化读目标层(真实参赛方),未物化读中间层名单。
     * 两个分支都按"这个赛段有哪些人"取,不掺晋级/淘汰的业务判断;未落位的人照样返回,
     * 只是 {@code seedRank=null, holding=true}。
     */
    @Override
    public StageParticipantsVo listStageParticipants(Long stageId) {
        return rosterViewService.listStageParticipants(stageId);
    }

    @Override
    public RosterPreviewVo previewAssembled(Long stageId) {
        return rosterViewService.previewAssembled(stageId);
    }

    // ------------------------------------------------------------------
    // 中间层名单(t_stage_roster_entry):两个赛段之间唯一的一份数据
    // ------------------------------------------------------------------

    /** 读中间层当前名单:按座位号升序,含空位行(BYE/PENDING) */
    @Override
    public List<TStageRosterEntry> entriesOf(Long targetStageId) {
        return rosterEntryStore.entriesOf(targetStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rebuildEntries(Long targetStageId) {
        return rosterEntryStore.rebuildEntries(targetStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rebuildEntriesOfDownstream(Long sourceStageId) {
        return rosterEntryStore.rebuildEntriesOfDownstream(sourceStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncPreAdvance(Long sourceStageId) {
        return rosterEntryStore.syncPreAdvance(sourceStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settlePendingSeatsOfDownstream(Long sourceStageId) {
        return rosterEntryStore.settlePendingSeatsOfDownstream(sourceStageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncPreAdvance(Long sourceStageId, Collection<Long> competitorIds) {
        return rosterEntryStore.syncPreAdvance(sourceStageId, competitorIds);
    }

}
