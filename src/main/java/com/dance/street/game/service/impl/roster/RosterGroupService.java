package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.service.impl.StageChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出口(来源组)配置与名单状态:赛段之间的依赖边 + 取人规则 + 由此推导的就绪/守卫。
 *
 * <p>从 {@code TStageRosterServiceImpl} 搬出来的第一块。出口只属于"来源 → 目标"这条边,
 * 所以规则求值、默认衔接、增删改查、状态推导都在这里;中间层的行读写仍归
 * {@link RosterEntryStore},两者通过 {@link RosterGroupStore} 的边表间接联动——
 * 规则一变,由本类触发中间层重建。</p>
 *
 * @author duane
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterGroupService {

    private final TStageMapper stageMapper;
    private final TStageRosterEntryMapper entryMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    /** 来源组(出口)的存储:读、按 id 差异保存、变更广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 出口取人规则的求值 */
    private final RosterAssembler rosterAssembler;
    /** 赛段链遍历的唯一入口(以 next 链为事实源) */
    private final StageChain stageChain;

    // ------------------------------------------------------------------
    // 状态位推导:名单已确认/已跳过(存在 t_stage 行上)与由此推出的展示状态
    // ------------------------------------------------------------------

    public boolean isApplied(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterApplied());
    }

    public boolean isSkipped(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterSkipped());
    }

    public boolean isLocked(TStage stage) {
        return isApplied(stage) || isSkipped(stage);
    }

    private TStage mustRosterStage(Long stageId, boolean editable) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (editable && isLocked(stage)) {
            throw new ServiceException("赛段名单已装配完成或已跳过,请先重置该赛段再调整");
        }
        return stage;
    }

    /** 名单展示状态:CONFIRMED/SKIPPED/READY/WAIT_SOURCE(推导,不再持久化) */
    private String stateOf(TStage stage) {
        return stateOf(stage, rosterGroupStore.groupsOf(stage), null);
    }

    /** 同上,来源赛段状态可预取(批量路径不再逐组回查) */
    private String stateOf(TStage stage, List<TStageRosterGroupBo> groups, Map<Long, TStage> sourceStages) {
        if (isApplied(stage)) {
            return RosterConstants.ROSTER_CONFIRMED;
        }
        if (isSkipped(stage)) {
            return RosterConstants.ROSTER_SKIPPED;
        }
        return rosterEntryStore.readyByGroups(groups, sourceStages) ? RosterConstants.ROSTER_READY : RosterConstants.ROSTER_WAIT_SOURCE;
    }

    // ------------------------------------------------------------------
    // 建段/改链/删段时的名单对账
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForStage(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return;
        }
        // 以库内最新状态为准:调用方可能传入改链前的旧 stage 对象
        TStage fresh = stageMapper.selectById(stage.getId());
        if (fresh == null) {
            return;
        }
        // 入口判定与默认来源组都以前驱为准;前驱由 next 链推导
        List<TStageRosterGroupBo> groups = new ArrayList<>(rosterGroupStore.groupsOf(fresh));
        // 只在"一条来源都没有"时补默认衔接:这是建段的初始值,删掉即永久生效
        // (此前按"有没有引用链上前驱的组"判断并到处补回,导致分支赛段永远删不掉假来源)
        int before = groups.size();
        ensurePrevChainDefault(fresh, groups);
        if (groups.size() != before) {
            rosterGroupStore.saveGroups(fresh, groups);
        }
        // 名单来源确定后就把中间层建出来(来源还没结算时是空表,结算事件会再触发重建)
        rosterEntryStore.rebuildEntries(fresh.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void reconcileAfterLinkChange(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }
        List<TStageRosterGroupBo> kept = new ArrayList<>();
        boolean changed = false;
        boolean streamSeen = false;
        // 前驱以 next 链推导:改链后本方法在事务内被调用,读到的已是新链
        TStage prevStage = stageChain.prevOf(stage);
        Long prevId = prevStage == null ? null : prevStage.getId();
        boolean entry = prevId == null;
        for (TStageRosterGroupBo g : rosterGroupStore.groupsOf(stage)) {
            boolean stream = RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || (g.getSourceStageId() == null && g.getFillMode() == null);
            if (entry) {
                if (stream) {
                    if (streamSeen) {
                        changed = true;
                        continue;
                    }
                    streamSeen = true;
                } else {
                    changed = true;
                    continue;
                }
            } else {
                if (stream) {
                    changed = true;
                    continue;
                }
                // 系统自动补的链式衔接:只有它引用的不是当前直接前驱时才清掉。
                // 按 generated 出处判断,不再看字段长相——出口面板配出来的自定义出口
                // 长得和默认组一模一样,按长相判断会把真实的分支依赖静默删掉。
                if (isGenerated(g) && !Objects.equals(g.getSourceStageId(), prevId)) {
                    changed = true;
                    continue;
                }
            }
            kept.add(g);
        }
        // 只要还剩任意一条来源,就不再自动补默认衔接(删掉就是删掉)
        boolean hasCurrentDefault = !kept.isEmpty();
        // 链路没有实际变化(例如只是保存赛段/生命周期状态回写)= 名单无需对账,
        // 直接返回,不受"名单已装配"锁定影响。
        if (!changed && hasCurrentDefault) {
            return;
        }
        // 确实要改名单时才要求未装配(装配后规则冻结,需先重置)
        if (isLocked(stage)) {
            throw new ServiceException("赛段[{}]名单已装配,请先重置该赛段后再调整赛段链路",
                stage.getName());
        }
        if (changed) {
            rosterGroupStore.saveGroups(stage, kept);
        }
        if (!hasCurrentDefault) {
            // 一条来源都不剩:补一条带 generated=1 的默认衔接(ensureRosterForStage 内部会重建)
            ensureRosterForStage(stage);
        } else if (changed) {
            // 来源组本身变了(例如跨级自定义出口的另一条被摘掉):
            // 规则变了就必须重建,否则中间层留着按旧出口算出来的人。
            rosterEntryStore.rebuildEntries(stage.getId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForSurvivors(Collection<Long> tournamentIds) {
        if (tournamentIds == null || tournamentIds.isEmpty()) {
            return;
        }
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getTournamentId, tournamentIds)
            .orderByAsc(TStage::getId))) {
            if (StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
                continue;
            }
            if (rosterGroupsMissing(stage)) {
                ensureRosterForStage(stage);
            }
        }
    }

    /** 名单来源组是否缺失(一条都没有):删除上游赛段把引用它的来源全部摘掉后就属于这种 */
    private boolean rosterGroupsMissing(TStage stage) {
        return rosterGroupStore.groupsOf(stage).isEmpty();
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSourceRefs(Collection<Long> deletedStageIds) {
        if (deletedStageIds == null || deletedStageIds.isEmpty()) {
            return;
        }
        Set<Long> deleted = new HashSet<>(deletedStageIds);
        // 只处理"引用了被删赛段"的目标:一次查询定位,不再扫全表逐个解析 JSON
        Set<Long> affected = rosterGroupStore.targetsReferencing(deleted);
        if (affected.isEmpty()) {
            return;
        }
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getId, affected))) {
            // 已取消的赛段不再参与流转,与 reconcileAfterLinkChange 一致直接跳过
            if (deleted.contains(stage.getId())
                || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
                continue;
            }
            List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(stage);
            int before = groups.size();
            groups.removeIf(g -> g.getSourceStageId() != null && deleted.contains(g.getSourceStageId()));
            if (groups.size() == before) {
                continue;
            }
            // 与 reconcileAfterLinkChange 同一把锁:已装配/已跳过的名单不允许被静默改写。
            // 否则会留下「来源组被摘空、rosterApplied 仍为 1」的假 CONFIRMED——
            // 名单状态显示已带入,参赛行却还指向被删除的赛段,开赛守卫还会放行。
            if (isLocked(stage)) {
                throw new ServiceException(
                    "赛段[{}]名单已装配/已跳过,删除上游赛段会破坏其名单来源;请先重置该赛段后再删除",
                    stage.getName());
            }
            rosterGroupStore.saveGroups(stage, groups);
            log.info("赛段删除:摘除赛段[{}]名单中引用已删赛段的来源组,剩余 {} 组",
                stage.getId(), groups.size());
            // 来源组变了 → 中间层作废重建(与"上游一变就全部重新来"同一口径)
            rosterEntryStore.rebuildEntries(stage.getId());
        }
    }

    // ------------------------------------------------------------------
    // 默认衔接(链式兜底)
    // ------------------------------------------------------------------

    /** 系统自动补的链式衔接:按 generated 出处判断(不看字段长相) */
    private boolean isGenerated(TStageRosterGroupBo g) {
        return Integer.valueOf(1).equals(g.getGenerated());
    }

    private TStageRosterGroupBo externalGroup() {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(null);
        g.setResultFilter(RosterConstants.FILTER_ANY);
        g.setFillMode(RosterConstants.FILL_STREAM);
        g.setQuota(0);
        return g;
    }

    private TStageRosterGroupBo defaultGroup(Long sourceStageId) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(0);
        return g;
    }

    private boolean sameGroup(TStageRosterGroupBo a, TStageRosterGroupBo b) {
        return Objects.equals(a.getSourceStageId(), b.getSourceStageId())
            && Objects.equals(a.getResultFilter(), b.getResultFilter())
            && Objects.equals(a.getZone(), b.getZone())
            && Objects.equals(a.getRound(), b.getRound())
            && Objects.equals(a.getRankStart(), b.getRankStart())
            && Objects.equals(a.getRankEnd(), b.getRankEnd())
            && Objects.equals(a.getRankByZone(), b.getRankByZone())
            && Objects.equals(a.getScoreMin(), b.getScoreMin())
            && Objects.equals(a.getScoreMax(), b.getScoreMax())
            && Objects.equals(a.getOrderBy(), b.getOrderBy());
    }

    /**
     * 链式默认衔接:名单必须至少有一条引用"直接前驱"的来源组;
     * 没有配置出口(或删光了引用前驱的组)时,自动补"上一赛段·晋级·AUTO"。
     * 入口赛段(无前驱)则保证存在签到来源组。
     */
    public void ensurePrevChainDefault(TStage stage, List<TStageRosterGroupBo> groups) {
        // 只在"一条来源都没有"时兜底:补一条带 generated=1 的默认衔接。
        // 已有任意来源时不插手——删掉默认衔接是使用者的明确意图,不能再补回来。
        if (!groups.isEmpty()) {
            return;
        }
        TStage prevStage = stageChain.prevOf(stage);
        Long prevId = prevStage == null ? null : prevStage.getId();
        if (prevId == null) {
            TStageRosterGroupBo stream = externalGroup();
            stream.setGenerated(1);
            groups.add(stream);
            log.info("入口赛段[{}]未配置来源,已补签到来源组", stage.getId());
            return;
        }
        TStageRosterGroupBo chain = defaultGroup(prevId);
        chain.setGenerated(1);
        groups.add(chain);
        log.info("赛段[{}]未配置出口,已补链式默认衔接:上一赛段[{}]晋级者进入本赛段",
            stage.getId(), prevId);
    }

    // ------------------------------------------------------------------
    // 只读视图:按目标 / 批量 / 按来源
    // ------------------------------------------------------------------

    public List<TStageRosterVo> listByTarget(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null) {
            return List.of();
        }
        return List.of(toVo(stage, rosterGroupStore.groupsOf(stage), rosterEntryStore.manualViewsOf(rosterEntryStore.entriesOf(stage.getId())), null));
    }

    /**
     * 批量取名单(赛段列表/导播台列表用):整页 3 条 SQL。
     *
     * <p>逐个 listByTarget 时,"每赛段一次名单查询 + 每来源组一次来源赛段查询"会随赛段数放大;
     * 这里一次性取回赛段、中间层行与全部被引用的来源赛段状态,在内存组装。</p>
     */
    public Map<Long, List<TStageRosterVo>> listByTargets(Collection<Long> targetStageIds) {
        if (targetStageIds == null || targetStageIds.isEmpty()) {
            return Map.of();
        }
        List<TStage> stages = stageMapper.selectByIds(targetStageIds.stream().distinct().toList());
        if (stages.isEmpty()) {
            return Map.of();
        }
        List<Long> stageIds = stages.stream().map(TStage::getId).toList();
        Map<Long, List<TStageRosterEntry>> entriesByStage = entryMapper
            .selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
                .in(TStageRosterEntry::getTargetStageId, stageIds)
                .orderByAsc(TStageRosterEntry::getSlot))
            .stream()
            .collect(Collectors.groupingBy(TStageRosterEntry::getTargetStageId));
        // 来源组一次批量取回(不再逐赛段查),同时收集它们引用的来源赛段供就绪度判定复用
        Map<Long, List<TStageRosterGroupBo>> groupsByStage = rosterGroupStore.groupsOfTargets(stageIds);
        Set<Long> sourceStageIds = new HashSet<>();
        for (TStage stage : stages) {
            for (TStageRosterGroupBo g : groupsByStage.getOrDefault(stage.getId(), List.of())) {
                if (g.getSourceStageId() != null && !stageIds.contains(g.getSourceStageId())) {
                    sourceStageIds.add(g.getSourceStageId());
                }
            }
        }
        Map<Long, TStage> sourceStages = new HashMap<>();
        stages.forEach(s -> sourceStages.put(s.getId(), s));
        if (!sourceStageIds.isEmpty()) {
            stageMapper.selectByIds(sourceStageIds).forEach(s -> sourceStages.put(s.getId(), s));
        }
        Map<Long, List<TStageRosterVo>> result = new HashMap<>();
        for (TStage stage : stages) {
            result.put(stage.getId(), List.of(toVo(stage, groupsByStage.getOrDefault(stage.getId(), List.of()),
                rosterEntryStore.manualViewsOf(entriesByStage.getOrDefault(stage.getId(), List.of())), sourceStages)));
        }
        return result;
    }

    public List<TStageRosterVo> listBySource(Long sourceStageId) {
        if (sourceStageId == null) {
            return List.of();
        }
        // 直接按边表查"谁引用了这个来源"(走 idx_roster_group_source),不再全库扫 JSON
        Set<Long> targetIds = rosterGroupStore.targetsReferencing(List.of(sourceStageId));
        if (targetIds.isEmpty()) {
            return List.of();
        }
        List<TStageRosterVo> out = new ArrayList<>();
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getId, targetIds)
            .orderByAsc(TStage::getId))) {
            out.add(toVo(stage, rosterGroupStore.groupsOf(stage),
                rosterEntryStore.manualViewsOf(rosterEntryStore.entriesOf(stage.getId())), null));
        }
        return out;
    }

    /**
     * 组装名单 VO。
     *
     * @param sourceStages 预取的来源赛段(id-&gt;赛段);为 null 时回退逐组查询(单个赛段接口的老路径)
     */
    private TStageRosterVo toVo(TStage stage, List<TStageRosterGroupBo> groups,
                                List<TStageRosterOverrideVo> overrides, Map<Long, TStage> sourceStages) {
        TStageRosterVo vo = new TStageRosterVo();
        vo.setId(stage.getId());
        vo.setTournamentId(stage.getTournamentId());
        vo.setTargetStageId(stage.getId());
        vo.setGroups(groups);
        vo.setState(stateOf(stage, groups, sourceStages));
        if (!groups.isEmpty()) {
            TStageRosterGroupBo first = groups.get(0);
            vo.setSourceStageId(first.getSourceStageId());
            vo.setResultFilter(first.getResultFilter());
            vo.setQuota(first.getQuota());
            vo.setFillMode(first.getFillMode());
            vo.setRankBandStart(first.getRankStart());
            vo.setRankBandEnd(first.getRankEnd());
            vo.setZoneFilter(first.getZone());
            vo.setRoundFilter(first.getRound());
            vo.setScoreMin(first.getScoreMin());
            vo.setScoreMax(first.getScoreMax());
        }
        vo.setOverrides(overrides);
        return vo;
    }

    // ------------------------------------------------------------------
    // 出口增删改查(仅规划中赛段可改)
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public TStageRosterVo addGroups(Long stageId, TStageRosterBo bo) {
        TStage target = stageMapper.selectById(stageId);
        if (target == null) {
            throw new ServiceException("目标赛段不存在");
        }
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可新增来源,当前: {}", target.getStatus());
        }
        // 只有已物化出参赛行时才代表名单真的被锁定过;空赛段(历史数据里被
        // 创建流程提前置 1)仍应允许配置来源组/出口,否则出口配置直接写不进去。
        if (Long.valueOf(1L).equals(target.getIsInitialized())
            && hasMaterializedCompetitors(stageId)) {
            throw new ServiceException("赛段已初始化,名单来源已锁定");
        }
        if (bo == null) {
            throw new ServiceException("请提供来源组规则");
        }
        List<TStageRosterGroupBo> desired = new ArrayList<>();
        if (bo.getGroups() != null && !bo.getGroups().isEmpty()) {
            desired.addAll(bo.getGroups());
        } else {
            TStageRosterGroupBo g = new TStageRosterGroupBo();
            g.setSourceStageId(bo.getSourceStageId());
            g.setResultFilter(bo.getResultFilter() == null
                ? OutcomeStatusEnum.ADVANCE.getCode() : bo.getResultFilter());
            g.setZone(bo.getZoneFilter());
            g.setRound(bo.getRoundFilter());
            g.setScoreMin(bo.getScoreMin());
            g.setScoreMax(bo.getScoreMax());
            g.setRankStart(bo.getRankBandStart());
            g.setRankEnd(bo.getRankBandEnd());
            g.setRankByZone(false);
            g.setFillMode(bo.getFillMode() == null
                ? RosterConstants.FILL_AUTO : bo.getFillMode());
            g.setQuota(bo.getQuota() == null ? 0 : bo.getQuota());
            desired.add(g);
        }
        if (desired.isEmpty()) {
            throw new ServiceException("请至少提供一条来源组规则");
        }
        List<TStageRosterGroupBo> merged = new ArrayList<>(rosterGroupStore.groupsOf(target));
        boolean changed = false;
        for (TStageRosterGroupBo g : desired) {
            if (g.getSourceStageId() == null) {
                g.setSourceStageId(bo.getSourceStageId());
            }
            if (g.getResultFilter() == null) {
                g.setResultFilter(bo.getResultFilter() == null
                    ? OutcomeStatusEnum.ADVANCE.getCode() : bo.getResultFilter());
            }
            if (g.getFillMode() == null) {
                g.setFillMode(bo.getFillMode() == null
                    ? RosterConstants.FILL_AUTO : bo.getFillMode());
            }
            if (g.getQuota() == null && bo.getQuota() != null) {
                g.setQuota(bo.getQuota());
            }
            if (g.getSourceStageId() != null) {
                TStage source = stageMapper.selectById(g.getSourceStageId());
                if (source == null || !Objects.equals(source.getTournamentId(), target.getTournamentId())) {
                    throw new ServiceException("来源赛段不存在或不属于同一赛事");
                }
                assertSourceBeforeTarget(target, source);
            }
            if (merged.stream().noneMatch(m -> sameGroup(m, g))) {
                merged.add(g);
                changed = true;
            }
        }
        if (changed) {
            rosterGroupStore.saveGroups(target, merged);
        }
        // 来源组变了:中间层按新规则全量重建(上游一变就全部重新来)
        rosterEntryStore.rebuildEntries(stageId);
        rosterGroupStore.notifyTarget(stageId);
        TStage fresh = stageMapper.selectById(stageId);
        return toVo(fresh, rosterGroupStore.groupsOf(fresh), rosterEntryStore.manualViewsOf(rosterEntryStore.entriesOf(stageId)), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeGroup(Long stageId, Long groupId) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可删除来源组,当前: {}", target.getStatus());
        }
        if (groupId == null) {
            throw new ServiceException("请指定要删除的来源组");
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(target);
        int index = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (Objects.equals(groups.get(i).getId(), groupId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ServiceException("来源组不存在或不属于本赛段: {}", groupId);
        }
        if (groups.size() <= 1) {
            throw new ServiceException("名单至少需要保留一组来源;如需清空请删除整个来源");
        }
        groups.remove(index);
        rosterGroupStore.saveGroups(target, groups);
        rosterEntryStore.rebuildEntries(stageId);
        log.info("赛段[{}]删除来源组[{}],剩余 {} 组", stageId, groupId, groups.size());
        rosterGroupStore.notifyTarget(stageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long stageId, Long groupId, TStageRosterGroupBo group) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可编辑来源组,当前: {}", target.getStatus());
        }
        if (groupId == null) {
            throw new ServiceException("请指定要编辑的来源组");
        }
        if (group == null) {
            throw new ServiceException("请提供来源组规则");
        }
        if (group.getSourceStageId() != null) {
            TStage source = stageMapper.selectById(group.getSourceStageId());
            if (source == null || !Objects.equals(source.getTournamentId(), target.getTournamentId())) {
                throw new ServiceException("来源赛段不存在或不属于同一赛事");
            }
            assertSourceBeforeTarget(target, source);
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(target);
        int index = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (Objects.equals(groups.get(i).getId(), groupId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ServiceException("来源组不存在或不属于本赛段: {}", groupId);
        }
        TStageRosterGroupBo old = groups.get(index);
        // 保持行 ID 不变:更新的是同一行,不再依赖数组下标
        group.setId(groupId);
        // 出处标记只在服务端维护:前端不传 generated 时保留原值,
        // 否则"编辑系统自动补的默认衔接"会被静默降级成人工出口(链变更时就不再清理它了)
        if (group.getGenerated() == null) {
            group.setGenerated(old.getGenerated());
        }
        if (group.getResultFilter() == null) {
            group.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        }
        if (group.getFillMode() == null) {
            group.setFillMode(RosterConstants.FILL_AUTO);
        }
        if (group.getQuota() == null) {
            group.setQuota(0);
        }
        groups.set(index, group);
        rosterGroupStore.saveGroups(target, groups);
        rosterEntryStore.rebuildEntries(stageId);
        log.info("赛段[{}]更新来源组[{}]", stageId, groupId);
        rosterGroupStore.notifyTarget(stageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorderGroups(Long stageId, List<Long> groupIds) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可调整取人顺序,当前: {}", target.getStatus());
        }
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(target);
        // 只接受本赛段现有的组 ID;未出现在列表里的组按原相对顺序接在后面
        List<TStageRosterGroupBo> ordered = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : groupIds) {
            for (TStageRosterGroupBo g : groups) {
                if (Objects.equals(g.getId(), id) && seen.add(id)) {
                    ordered.add(g);
                }
            }
        }
        for (TStageRosterGroupBo g : groups) {
            if (g.getId() == null || !seen.contains(g.getId())) {
                ordered.add(g);
            }
        }
        if (ordered.size() != groups.size()) {
            throw new ServiceException("取人顺序列表与现有来源组不匹配,请刷新后重试");
        }
        rosterGroupStore.saveGroups(target, ordered);
        rosterEntryStore.rebuildEntries(stageId);
        log.info("赛段[{}]来源组取人顺序已更新:{}", stageId, groupIds);
        rosterGroupStore.notifyTarget(stageId);
    }

    // ------------------------------------------------------------------
    // 跳过 / 重置
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void markSkipped(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            return;
        }
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setRosterSkipped(1L);
        stageMapper.updateById(upd);
        // 跳过后本赛段不带人:中间层清空(避免读路径仍显示一份名单)
        rosterEntryStore.clearEntries(stageId);
        rosterGroupStore.notifyTarget(stageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetByTarget(Long targetStageId) {
        TStage upd = new TStage();
        upd.setId(targetStageId);
        upd.setRosterApplied(0L);
        upd.setRosterSkipped(0L);
        stageMapper.updateById(upd);
        // 只撤回"已确认/已跳过"两个状态位:中间层的行(含人工调整)原样保留,
        // 它是重新确认时的起点;真要按上游重算,走 rosterEntryStore.rebuildEntries(4.8)。
        rosterGroupStore.notifyTarget(targetStageId);
    }

    // ------------------------------------------------------------------
    // 候选源与开赛守卫
    // ------------------------------------------------------------------

    public void assertNoPendingInSources(List<TStageRosterGroupBo> groups) {
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (src == null) {
                continue;
            }
            long pending = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, src.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            if (pending > 0) {
                throw new ServiceException(
                    "来源赛段[{}]仍有 {} 名同分待定参赛方未裁决,请先在中间态处理后再确认名单",
                    src.getName(), pending);
            }
        }
    }

    public RosterCandidatesVo candidates(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterCandidatesVo vo = new RosterCandidatesVo();
        vo.setStageId(stageId);
        vo.setState(stateOf(stage));
        List<RosterCandidatesVo.GroupCandidates> groupsVo = new ArrayList<>();
        for (TStageRosterGroupBo g : rosterGroupStore.groupsOf(stage)) {
            RosterCandidatesVo.GroupCandidates gv = new RosterCandidatesVo.GroupCandidates();
            gv.setResultFilter(g.getResultFilter());
            gv.setZone(g.getZone());
            gv.setRankStart(g.getRankStart());
            gv.setRankEnd(g.getRankEnd());
            gv.setRankByZone(g.getRankByZone());
            gv.setRound(g.getRound());
            gv.setScoreMin(g.getScoreMin());
            gv.setScoreMax(g.getScoreMax());
            gv.setLabel(groupLabel(g));
            gv.setSourceStageId(g.getSourceStageId());
            gv.setCompetitors(rosterAssembler.groupRows(g).stream()
                .map(c -> MapstructUtils.convert(c, TCompetitorVo.class))
                .toList());
            groupsVo.add(gv);
        }
        vo.setGroups(groupsVo);
        return vo;
    }

    public boolean hasAnyCandidate(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            return false;
        }
        for (TStageRosterGroupBo g : rosterGroupStore.groupsOf(stage)) {
            if (!rosterAssembler.groupRows(g).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean isRosterReady(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return stage != null && rosterEntryStore.readyByGroups(rosterGroupStore.groupsOf(stage));
    }

    public void assertStageStartable(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null) {
            return;
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(stage);
        boolean hasInternalSource = groups.stream().anyMatch(g -> g.getSourceStageId() != null);
        if (!hasInternalSource) {
            // 纯签到/人工名单:与本赛段的衔接无关,不设限
            return;
        }
        String state = stateOf(stage);
        if (RosterConstants.ROSTER_CONFIRMED.equals(state)
            || RosterConstants.ROSTER_SKIPPED.equals(state)) {
            log.info("赛段[{}]名单已装配/跳过,开赛放行", targetStageId);
            return;
        }
        if (!rosterEntryStore.readyByGroups(groups)) {
            // 依赖 = 来源组里的边:报错时点名是哪几段还没结束,现场好对
            throw new ServiceException("名单来源[{}]尚未结束,等这些赛段结算并确认名单后再开始本赛段",
                String.join("、", unsettledSourceNames(groups)));
        }
        if (hasAnyCandidate(targetStageId)) {
            throw new ServiceException("赛段名单尚未确认,请先在中间态「确认名单」后再开始本赛段");
        }
        // 确无任何来源候选:本赛段不带人,直接放行(不写任何状态)
        log.info("赛段[{}]名单无来源候选,本赛段不带人,直接开赛", targetStageId);
    }

    /** 还没结束(SETTLED)的来源赛段名(用于开赛守卫的报错文案) */
    private List<String> unsettledSourceNames(List<TStageRosterGroupBo> groups) {
        List<String> names = new ArrayList<>();
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (src == null) {
                names.add("赛段#" + g.getSourceStageId());
            } else if (!StageConstants.STAGE_SETTLED.equals(src.getStatus())) {
                names.add(src.getName());
            }
        }
        return names;
    }

    /** 出口规则的展示文案(候选来源面板) */
    private String groupLabel(TStageRosterGroupBo g) {
        String filter = g.getResultFilter();
        String result = OutcomeStatusEnum.ADVANCE.getCode().equals(filter) ? "晋级"
            : OutcomeStatusEnum.ELIMINATED.getCode().equals(filter) ? "落选"
            : filter == null ? "不限" : filter;
        String rank = g.getRankStart() != null || g.getRankEnd() != null
            ? (g.getRankStart() == null ? "" : g.getRankStart()) + "~"
                + (g.getRankEnd() == null ? "末" : g.getRankEnd()) + "名"
            : "";
        if (g.getZone() != null) {
            Map<String, Integer> order = g.getSourceStageId() == null ? Map.of()
                : zoneOrderOf(g.getSourceStageId());
            // 圈名按圈序号解析:第 k 个圈的分区名就是 ZONE-k
            return "第" + (order.getOrDefault(rosterAssembler.normalizeZone(g.getZone()), -1) + 1) + "圈·" + result + rank;
        }
        return (Boolean.TRUE.equals(g.getRankByZone()) ? "每圈" : "全场") + result + rank;
    }

    private Map<String, Integer> zoneOrderOf(Long sourceStageId) {
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, sourceStageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        return rosterAssembler.zoneByIdOrder(matches);
    }

    /** 该赛段是否已物化出参赛行(名单真正被锁定过);空赛段不算 */
    public boolean hasMaterializedCompetitors(Long stageId) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)) > 0;
    }

    /**
     * 加"来源 → 目标"这条边前的校验:同赛事、非自身、<b>来源必须排在目标之前</b>。
     *
     * <p>依赖关系以来源组(边)为事实源,链只决定显示顺序——而显示顺序本身就是拓扑序,
     * 于是"只能连向链上靠后的赛段"这一条约束天然保证了不会成环,不需要额外的环检测。
     * 分支/汇合(一个来源连多个目标、多个来源连同一个目标)都不受影响:
     * 它们的方向始终是往后。</p>
     */
    private void assertSourceBeforeTarget(TStage target, TStage source) {
        if (Objects.equals(source.getId(), target.getId())) {
            throw new ServiceException("来源赛段不能是目标赛段自身");
        }
        if (!Objects.equals(source.getTournamentId(), target.getTournamentId())) {
            throw new ServiceException("来源赛段与目标赛段不属于同一赛事");
        }
        List<TStage> chain = stageChain.orderedChain(target.getTournamentId());
        int sourceIndex = indexInChain(chain, source.getId());
        int targetIndex = indexInChain(chain, target.getId());
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex >= targetIndex) {
            throw new ServiceException("来源赛段[{}]必须排在目标赛段[{}]之前(只能把后面的赛段作为去向)",
                source.getName(), target.getName());
        }
    }

    private int indexInChain(List<TStage> chain, Long stageId) {
        for (int i = 0; i < chain.size(); i++) {
            if (Objects.equals(chain.get(i).getId(), stageId)) {
                return i;
            }
        }
        return -1;
    }
}
