package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterGroup;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TStageRosterGroupMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.TournamentEventNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 名单服务实现:一份数据三个层次——
 * <ul>
 *   <li>来源层:上游赛段的参赛方与成绩(只读);</li>
 *   <li>中间层:{@code t_stage_roster_entry} 一行 = 一个座位(有人/轮空都要占号),规则一变就整体重建,
 *       人工调整直接增删改这些行,读路径零计算;</li>
 *   <li>目标层:确认名单后物化出的 {@code t_competitor(stage=下一赛段, from_roster=1)}。</li>
 * </ul>
 * 规则存在独立边表 {@code t_stage_roster_group}(赛段间依赖的边+取人规则);
 * 状态位({@code roster_applied/roster_skipped})留在赛段行上。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TStageRosterServiceImpl implements ITStageRosterService {

    /** 按姓名新建外卡选手时写入 t_player.tags 的标签(json 列,与导入口径一致) */
    private static final String GUEST_PLAYER_TAGS = "[\"GUEST\"]";

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    /** 中间层名单(两个赛段之间的唯一事实) */
    private final TStageRosterEntryMapper entryMapper;
    /** 名单来源组(赛段间依赖的边+取人规则)的唯一事实源 */
    private final TStageRosterGroupMapper groupMapper;
    private final TPlayerMapper playerMapper;
    private final TournamentEventNotifier tournamentEventNotifier;
    /** 赛段链遍历的唯一入口(以 next 链为事实源) */
    private final StageChain stageChain;

    // ------------------------------------------------------------------
    // 名单来源组(赛段间依赖的边+取人规则)的读写
    //
    // 事实源 = t_stage_roster_group 表;t_stage 上只留 roster_applied/roster_skipped 两个状态位。
    // 取人顺序按 sort_order 升序,不再依赖 JSON 数组顺序。
    // ------------------------------------------------------------------

    /**
     * 读某赛段的来源组:按取人顺序({@code sort_order})升序。
     *
     * <p>对上层调用点保持了原来的签名,批量场景请用 {@link #groupsOfTargets} 避免 N+1。</p>
     */
    private List<TStageRosterGroupBo> groupsOf(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return List.of();
        }
        return selectGroups(stage.getId());
    }

    private List<TStageRosterGroupBo> selectGroups(Long targetStageId) {
        // 返回可变的 ArrayList:调用方习惯在返回列表上原地增删改(removeGroup/addGroups/...)
        return groupMapper.selectList(Wrappers.<TStageRosterGroup>lambdaQuery()
                .eq(TStageRosterGroup::getTargetStageId, targetStageId)
                .orderByAsc(TStageRosterGroup::getSortOrder)
                .orderByAsc(TStageRosterGroup::getId))
            .stream().map(TStageRosterServiceImpl::toGroupBo)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public List<TStageRosterGroupBo> groupsOfStage(Long targetStageId) {
        return targetStageId == null ? List.of() : selectGroups(targetStageId);
    }

    /** 批量读多个目标赛段的来源组:一次 IN 查询 + 内存分组(替代逐段查询) */
    private Map<Long, List<TStageRosterGroupBo>> groupsOfTargets(Collection<Long> targetStageIds) {
        if (targetStageIds == null || targetStageIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = targetStageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TStageRosterGroupBo>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, new ArrayList<>());
        }
        groupMapper.selectList(Wrappers.<TStageRosterGroup>lambdaQuery()
                .in(TStageRosterGroup::getTargetStageId, ids)
                .orderByAsc(TStageRosterGroup::getSortOrder)
                .orderByAsc(TStageRosterGroup::getId))
            .forEach(g -> result.computeIfAbsent(g.getTargetStageId(), k -> new ArrayList<>())
                .add(toGroupBo(g)));
        return result;
    }

    /** 引用了这些来源赛段的目标赛段 ID(一次查询,替代"扫全表逐个解析 JSON") */
    private Set<Long> targetsReferencing(Collection<Long> sourceStageIds) {
        if (sourceStageIds == null || sourceStageIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = sourceStageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return groupMapper.selectList(Wrappers.<TStageRosterGroup>lambdaQuery()
                .in(TStageRosterGroup::getSourceStageId, ids)
                .select(TStageRosterGroup::getTargetStageId))
            .stream().map(TStageRosterGroup::getTargetStageId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private static TStageRosterGroupBo toGroupBo(TStageRosterGroup g) {
        TStageRosterGroupBo bo = new TStageRosterGroupBo();
        bo.setId(g.getId());
        bo.setSourceStageId(g.getSourceStageId());
        bo.setResultFilter(g.getResultFilter());
        bo.setZone(g.getZone());
        bo.setRound(g.getRoundNo());
        bo.setRankStart(g.getRankStart());
        bo.setRankEnd(g.getRankEnd());
        bo.setRankByZone(Integer.valueOf(1).equals(g.getRankByZone()));
        bo.setScoreMin(g.getScoreMin());
        bo.setScoreMax(g.getScoreMax());
        bo.setFillMode(g.getFillMode());
        bo.setQuota(g.getQuota());
        bo.setOrderBy(g.getOrderBy());
        bo.setSortOrder(g.getSortOrder());
        bo.setGenerated(g.getAutoGenerated());
        return bo;
    }

    private boolean isApplied(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterApplied());
    }

    private boolean isSkipped(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterSkipped());
    }

    private boolean isLocked(TStage stage) {
        return isApplied(stage) || isSkipped(stage);
    }

    /**
     * 保存来源组:按 id diff(新增 insert / 已有 update / 缺失 delete),不整包重写。
     *
     * <p>按 id 而不是"删光重插":否则每次保存都会换一遍行 ID,前端手里的 ID 立刻失效。</p>
     */
    private void saveGroups(TStage stage, List<TStageRosterGroupBo> groups, Long applied, Long skipped) {
        replaceGroups(stage, groups);
        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setRosterApplied(applied);
        upd.setRosterSkipped(skipped);
        stageMapper.updateById(upd);
    }

    private void replaceGroups(TStage stage, List<TStageRosterGroupBo> groups) {
        List<TStageRosterGroup> existing = groupMapper.selectList(Wrappers.<TStageRosterGroup>lambdaQuery()
            .eq(TStageRosterGroup::getTargetStageId, stage.getId()));
        Map<Long, TStageRosterGroup> byId = existing.stream()
            .filter(g -> g.getId() != null)
            .collect(Collectors.toMap(TStageRosterGroup::getId, g -> g, (a, b) -> a));
        List<TStageRosterGroupBo> wanted = groups == null ? List.of() : groups;
        Set<Long> kept = new HashSet<>();
        int order = 1;
        for (TStageRosterGroupBo bo : wanted) {
            TStageRosterGroup row = bo.getId() == null ? null : byId.get(bo.getId());
            if (row == null) {
                groupMapper.insert(newGroupRow(stage, bo, order++));
            } else {
                applyGroupBo(row, bo, order++);
                groupMapper.updateById(row);
                kept.add(row.getId());
            }
        }
        for (TStageRosterGroup row : existing) {
            if (row.getId() != null && !kept.contains(row.getId())) {
                groupMapper.deleteById(row.getId());
            }
        }
    }

    private TStageRosterGroup newGroupRow(TStage stage, TStageRosterGroupBo bo, int order) {
        TStageRosterGroup row = new TStageRosterGroup();
        row.setTournamentId(stage.getTournamentId());
        row.setTargetStageId(stage.getId());
        applyGroupBo(row, bo, order);
        return row;
    }

    private void applyGroupBo(TStageRosterGroup row, TStageRosterGroupBo bo, int order) {
        row.setSourceStageId(bo.getSourceStageId());
        row.setResultFilter(bo.getResultFilter());
        row.setZone(bo.getZone());
        row.setRoundNo(bo.getRound());
        row.setRankStart(bo.getRankStart());
        row.setRankEnd(bo.getRankEnd());
        row.setRankByZone(Boolean.TRUE.equals(bo.getRankByZone()) ? 1 : 0);
        row.setScoreMin(bo.getScoreMin());
        row.setScoreMax(bo.getScoreMax());
        row.setFillMode(bo.getFillMode() == null ? RosterConstants.FILL_AUTO : bo.getFillMode());
        row.setQuota(bo.getQuota() == null ? 0 : bo.getQuota());
        row.setOrderBy(bo.getOrderBy());
        // 取人顺序以"传入列表的顺序"为唯一口径:无论编辑单条、删除还是重排,
        // 调用方给的都是期望顺序,按 order 重排即可,不让 BO 里的旧值再插一脚。
        row.setSortOrder(order);
        row.setAutoGenerated(bo.getGenerated() == null ? 0 : bo.getGenerated());
    }

    private void saveGroups(TStage stage, List<TStageRosterGroupBo> groups) {
        saveGroups(stage, groups, stage.getRosterApplied(), stage.getRosterSkipped());
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
        return stateOf(stage, groupsOf(stage), null);
    }

    /** 同上,来源赛段状态可预取(批量路径不再逐组回查) */
    private String stateOf(TStage stage, List<TStageRosterGroupBo> groups, Map<Long, TStage> sourceStages) {
        if (isApplied(stage)) {
            return RosterConstants.ROSTER_CONFIRMED;
        }
        if (isSkipped(stage)) {
            return RosterConstants.ROSTER_SKIPPED;
        }
        return readyByGroups(groups, sourceStages) ? RosterConstants.ROSTER_READY : RosterConstants.ROSTER_WAIT_SOURCE;
    }

    @Override
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
        List<TStageRosterGroupBo> groups = new ArrayList<>(groupsOf(fresh));
        // 只在"一条来源都没有"时补默认衔接:这是建段的初始值,删掉即永久生效
        // (此前按"有没有引用链上前驱的组"判断并到处补回,导致分支赛段永远删不掉假来源)
        int before = groups.size();
        ensurePrevChainDefault(fresh, groups);
        if (groups.size() != before) {
            saveGroups(fresh, groups);
        }
        // 名单来源确定后就把中间层建出来(来源还没结算时是空表,结算事件会再触发重建)
        rebuildEntries(fresh.getId());
    }

    @Override
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
        for (TStageRosterGroupBo g : groupsOf(stage)) {
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
            saveGroups(stage, kept);
        }
        if (!hasCurrentDefault) {
            // 一条来源都不剩:补一条带 generated=1 的默认衔接(ensureRosterForStage 内部会重建)
            ensureRosterForStage(stage);
        } else if (changed) {
            // 来源组本身变了(例如跨级自定义出口的另一条被摘掉):
            // 规则变了就必须重建,否则中间层留着按旧出口算出来的人。
            rebuildEntries(stage.getId());
        }
    }

    @Override
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
        return groupsOf(stage).isEmpty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSourceRefs(Collection<Long> deletedStageIds) {
        if (deletedStageIds == null || deletedStageIds.isEmpty()) {
            return;
        }
        Set<Long> deleted = new HashSet<>(deletedStageIds);
        // 只处理"引用了被删赛段"的目标:一次查询定位,不再扫全表逐个解析 JSON
        Set<Long> affected = targetsReferencing(deleted);
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
            List<TStageRosterGroupBo> groups = groupsOf(stage);
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
            saveGroups(stage, groups);
            log.info("赛段删除:摘除赛段[{}]名单中引用已删赛段的来源组,剩余 {} 组",
                stage.getId(), groups.size());
            // 来源组变了 → 中间层作废重建(与"上游一变就全部重新来"同一口径)
            rebuildEntries(stage.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeEntriesOfTargets(Collection<Long> targetStageIds) {
        if (targetStageIds == null || targetStageIds.isEmpty()) {
            return;
        }
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .in(TStageRosterEntry::getTargetStageId, targetStageIds));
    }

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
    private void ensurePrevChainDefault(TStage stage, List<TStageRosterGroupBo> groups) {
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

    /** 名单就绪度(纯函数):全部内部来源组已结算 */
    private boolean readyByGroups(List<TStageRosterGroupBo> groups) {
        return readyByGroups(groups, null);
    }

    /**
     * 能否把中间层物化出来(展示口径):来源赛段<b>已开赛</b>或已结算即可,不要求全部结算。
     *
     * <p>淘汰赛每判完一场就把胜者标成 ADVANCE 并写回名次(见 {@code DownstreamRouter.markAdvance}),
     * 所以"上一个赛段晋级了谁"在赛段还在进行时就已经有数据了 —— 中间态、大屏预排要实时看到这些人,
     * 不能等整个赛段结算。未结算期间生成的行 status=PENDING 作为标记。</p>
     *
     * <p>注意:这只是<b>展示</b>门槛。"确认名单 / 开赛守卫"仍然要求
     * {@link #readyByGroups}(全部来源已结算),否则会把人还没打完的半成品名单物化进下一赛段。</p>
     */
    private boolean materializableByGroups(List<TStageRosterGroupBo> groups) {
        if (groups.isEmpty()) {
            return false;
        }
        for (TStageRosterGroupBo g : groups) {
            if (RosterConstants.FILL_STREAM.equals(g.getFillMode()) || g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (src == null || StageConstants.STAGE_DISCARD.equals(src.getStatus())) {
                return false;
            }
            boolean started = StageConstants.STAGE_GAMING.equals(src.getStatus())
                || StageConstants.STAGE_SETTLED.equals(src.getStatus());
            if (!started) {
                return false;
            }
        }
        return true;
    }

    /** 同上,来源赛段可预取(批量路径一次取回,避免逐组 selectById) */
    private boolean readyByGroups(List<TStageRosterGroupBo> groups, Map<Long, TStage> sourceStages) {
        if (groups.isEmpty()) {
            return false;
        }
        for (TStageRosterGroupBo g : groups) {
            if (RosterConstants.FILL_STREAM.equals(g.getFillMode()) || g.getSourceStageId() == null) {
                continue;
            }
            TStage src = sourceStages != null
                ? sourceStages.get(g.getSourceStageId())
                : stageMapper.selectById(g.getSourceStageId());
            if (src == null || !StageConstants.STAGE_SETTLED.equals(src.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否"多入口汇合":名单来源组引用了两个及以上不同的上游赛段。
     *
     * <p>单入口(含分圈海选给同一赛段配的多条出口)仍然按规则自动排座:淘汰赛的"名次=座位"
     * 是有意义的(签表位置、轮空留下的空洞都靠它),海选按圈名次轮转也是合理的。
     * 多入口时两条线各自从第 1 名开始,系统没有任何依据决定谁坐哪,于是<b>全部进待落位区</b>,
     * 由导播在中间态拖到真实座位;确认名单前必须全部落位。</p>
     */
    private boolean multiEntry(List<TStageRosterGroupBo> groups) {
        return groups.stream()
            .map(TStageRosterGroupBo::getSourceStageId)
            .filter(Objects::nonNull)
            .distinct()
            .count() > 1;
    }

    @Override
    public List<TStageRosterVo> listByTarget(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null) {
            return List.of();
        }
        return List.of(toVo(stage, groupsOf(stage), manualViewsOf(entriesOf(stage.getId())), null));
    }

    /**
     * 批量取名单(赛段列表/导播台列表用):整页 3 条 SQL。
     *
     * <p>逐个 listByTarget 时,"每赛段一次名单查询 + 每来源组一次来源赛段查询"会随赛段数放大;
     * 这里一次性取回赛段、中间层行与全部被引用的来源赛段状态,在内存组装。</p>
     */
    @Override
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
        Map<Long, List<TStageRosterGroupBo>> groupsByStage = groupsOfTargets(stageIds);
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
                manualViewsOf(entriesByStage.getOrDefault(stage.getId(), List.of())), sourceStages)));
        }
        return result;
    }

    @Override
    public List<TStageRosterVo> listBySource(Long sourceStageId) {
        if (sourceStageId == null) {
            return List.of();
        }
        // 直接按边表查"谁引用了这个来源"(走 idx_roster_group_source),不再全库扫 JSON
        Set<Long> targetIds = targetsReferencing(List.of(sourceStageId));
        if (targetIds.isEmpty()) {
            return List.of();
        }
        List<TStageRosterVo> out = new ArrayList<>();
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getId, targetIds)
            .orderByAsc(TStage::getId))) {
            out.add(toVo(stage, groupsOf(stage),
                manualViewsOf(entriesOf(stage.getId())), null));
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
    // 人工调整(就是中间层里的行)
    // ------------------------------------------------------------------

    @Override
    public List<TStageRosterOverrideVo> listOverrides(Long stageId) {
        return manualViewsOf(entriesOf(stageId));
    }

    /** 中间层行 → 人工调整视图:手工加进来的(origin=MANUAL)与被移出的空位(BYE 且带来源引用) */
    private List<TStageRosterOverrideVo> manualViewsOf(List<TStageRosterEntry> entries) {
        return entries.stream()
            .filter(e -> RosterConstants.ENTRY_ORIGIN_MANUAL.equals(e.getOrigin())
                || (StageConstants.SLOT_BYE.equals(e.getSlotKind()) && e.getSourceCompetitorId() != null))
            .map(this::toOverrideVo)
            .toList();
    }

    private TStage assertOverrideEditable(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (isLocked(stage)) {
            throw new ServiceException("名单已装配完成或已跳过,请先重置目标赛段再调整人工覆盖");
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可编辑人工覆盖,当前: {}", stage.getStatus());
        }
        // 同上:只有已物化出参赛行时,"已初始化"才代表名单真的被锁定过
        if (Long.valueOf(1L).equals(stage.getIsInitialized()) && hasMaterializedCompetitors(stageId)) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法调整人工覆盖");
        }
        // 上一赛段还没结束:中间态只读。实时显示的是"打到这里为止已晋级的人",
        // 此时加人/拖位没有意义——后面每判完一场,名次座位都会按上游结果覆盖一次。
        List<TStageRosterGroupBo> groups = groupsOf(stage);
        if (!groups.isEmpty() && !readyByGroups(groups)) {
            throw new ServiceException("上一赛段还没结束,中间态暂不能调整(能实时看到已晋级的选手,"
                + "等来源赛段结算完成后再改)");
        }
        return stage;
    }

    /** 该赛段是否已物化出参赛行(名单真正被锁定过);空赛段不算 */
    private boolean hasMaterializedCompetitors(Long stageId) {
        return competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageRosterOverrideVo addOverride(Long stageId, TStageRosterOverrideBo bo) {
        TStage target = assertOverrideEditable(stageId);
        if (bo == null || bo.getOp() == null) {
            throw new ServiceException("请指定覆盖操作(op)");
        }
        String op = bo.getOp();
        if (!List.of(RosterConstants.OVERRIDE_ADD_SOURCE, RosterConstants.OVERRIDE_ADD_GUEST,
            RosterConstants.OVERRIDE_REMOVE, RosterConstants.OVERRIDE_SEED).contains(op)) {
            throw new ServiceException("不支持的覆盖操作: {}", op);
        }
        validateOverrideSource(stageId, op, bo);
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)) {
            validateGuestProfile(target, bo);
            // 去重必须放在"按姓名建选手"之前:否则第二次同名会先建出一名新选手,
            // 再按新 playerId 查重自然查不到,同名外卡就被放进名单了
            if (guestExists(stageId, bo)) {
                throw new ServiceException("同类型覆盖已存在,请先删除或编辑原覆盖");
            }
        }
        if (bo.getSeedRank() != null && !RosterConstants.OVERRIDE_REMOVE.equals(op)) {
            validateSeedWithinPlan(target, bo.getSeedRank());
        }
        // 只填姓名加外卡时:同步建一条选手(t_player)并回填 playerId,外卡物化时才带得上成员行
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)
            && (bo.getPlayerId() == null || bo.getPlayerId() <= 0L)) {
            TPlayer guestPlayer = new TPlayer();
            guestPlayer.setTenantId(target.getTenantId());
            guestPlayer.setTournamentId(target.getTournamentId());
            guestPlayer.setName(bo.getGuestName().trim());
            guestPlayer.setTags(GUEST_PLAYER_TAGS);
            guestPlayer.setRemark("名单加人-输入姓名自动创建");
            playerMapper.insert(guestPlayer);
            bo.setPlayerId(guestPlayer.getId());
            log.info("赛段[{}]外卡[{}]按姓名新建选手[id={}]",
                stageId, guestPlayer.getName(), guestPlayer.getId());
        }
        TStageRosterEntry row;
        if (RosterConstants.OVERRIDE_REMOVE.equals(op)) {
            // 移出 = 该座位留下一个空位实体行(保留来源引用,便于撤销;后面的座位不前移)
            row = entryOfSource(stageId, bo.getSourceCompetitorId());
            if (row == null) {
                // 本来就不在名单里(规则没选中):无需处理
                return null;
            }
            row.setSlotKind(StageConstants.SLOT_BYE);
            row.setEntryTag(null);
            entryMapper.updateById(row);
            row = entryMapper.selectById(row.getId());
        } else if (RosterConstants.OVERRIDE_SEED.equals(op)) {
            row = entryOfSource(stageId, bo.getSourceCompetitorId());
            if (row == null) {
                // 规则没选中,但要求钉在某个座位:等价于"拉进来 + 钉座位"
                row = insertManualRow(target, stageId, bo, bo.getSeedRank());
            } else {
                // 钉座位 = 与占位方互换(空位行也一样被换走),其他人不动
                TStageRosterEntry occupant = entryOfSlot(stageId, bo.getSeedRank());
                if (occupant != null && !Objects.equals(occupant.getId(), row.getId())) {
                    Long rowSlot = row.getSlot();
                    occupant.setSlot(rowSlot);
                    entryMapper.updateById(occupant);
                }
                row.setSlot(bo.getSeedRank());
                entryMapper.updateById(row);
                row = entryMapper.selectById(row.getId());
            }
        } else {
            row = entryOfSource(stageId, bo.getSourceCompetitorId());
            if (row != null && RosterConstants.OVERRIDE_ADD_SOURCE.equals(op)) {
                // 之前被移出过:直接把这个座位恢复成人,不新增行
                row.setSlotKind(StageConstants.SLOT_PLAYER);
                row.setEntryTag(entryTagOf(bo.getSourceCompetitorId()));
                entryMapper.updateById(row);
            } else {
                long slot = bo.getSeedRank() != null && bo.getSeedRank() > 0
                    ? bo.getSeedRank() : nextFreeSlot(target, stageId);
                TStageRosterEntry occupant = entryOfSlot(stageId, slot);
                if (occupant != null && StageConstants.SLOT_PLAYER.equals(occupant.getSlotKind())
                    && bo.getSeedRank() != null) {
                    throw new ServiceException("种子位[{}]已被占用,请先在中间态调整预排位置", slot);
                }
                if (occupant != null) {
                    entryMapper.deleteById(occupant.getId()); // 占的是空位:原地换人,座位号不变
                }
                row = insertManualRow(target, stageId, bo, slot);
            }
        }
        log.info("赛段[{}]新增人工调整[{}]({})", stageId, row.getId(), op);
        notifyTarget(stageId);
        return toOverrideVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOverride(Long stageId, Long overrideId, TStageRosterOverrideBo bo) {
        TStage target = assertOverrideEditable(stageId);
        TStageRosterEntry e = overrideId == null ? null : entryMapper.selectById(overrideId);
        if (e == null || !Objects.equals(e.getTargetStageId(), stageId)) {
            throw new ServiceException("覆盖不存在或不属于该名单");
        }
        if (bo == null) {
            throw new ServiceException("请提供覆盖内容");
        }
        if ("GUEST".equals(e.getRefType())) {
            if (bo.getGuestName() != null) {
                e.setGuestName(bo.getGuestName().trim());
            }
            if (bo.getPlayerId() != null) {
                e.setPlayerId(bo.getPlayerId());
            }
            if (bo.getGuestType() != null) {
                e.setGuestType(bo.getGuestType());
            }
            if (bo.getGuestNumber() != null) {
                e.setGuestNumber(bo.getGuestNumber());
            }
            validateGuestProfile(target, guestBoOf(e));
        }
        if (bo.getSeedRank() != null) {
            validateSeedWithinPlan(target, bo.getSeedRank());
            e.setSlot(bo.getSeedRank());
        }
        if (bo.getRemark() != null) {
            e.setRemark(bo.getRemark());
        }
        entryMapper.updateById(e);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverride(Long stageId, Long overrideId) {
        assertOverrideEditable(stageId);
        TStageRosterEntry e = overrideId == null ? null : entryMapper.selectById(overrideId);
        if (e == null || !Objects.equals(e.getTargetStageId(), stageId)) {
            return;
        }
        if (StageConstants.SLOT_BYE.equals(e.getSlotKind()) && e.getSourceCompetitorId() != null) {
            // 撤销"移出":把这个座位的人放回来
            e.setSlotKind(StageConstants.SLOT_PLAYER);
            e.setEntryTag(entryTagOf(e.getSourceCompetitorId()));
            entryMapper.updateById(e);
        } else if (RosterConstants.ENTRY_ORIGIN_MANUAL.equals(e.getOrigin())) {
            // 撤销"手工加人":座位还原成空位(实体行保留,后面的座位不前移)
            entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, e.getId())
                .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_BYE)
                .set(TStageRosterEntry::getOrigin, RosterConstants.ENTRY_ORIGIN_RULE)
                .set(TStageRosterEntry::getRefType, null)
                .set(TStageRosterEntry::getSourceCompetitorId, null)
                .set(TStageRosterEntry::getSourceStageId, null)
                .set(TStageRosterEntry::getPlayerId, null)
                .set(TStageRosterEntry::getGuestName, null)
                .set(TStageRosterEntry::getGuestNumber, null)
                .set(TStageRosterEntry::getEntryTag, null));
        } else {
            throw new ServiceException("该条目不是人工调整,无法撤销");
        }
        log.info("赛段[{}]撤销人工条目[{}]", stageId, overrideId);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items) {
        TStage target = assertOverrideEditable(stageId);
        if (items == null || items.isEmpty()) {
            return;
        }
        // 两阶段落位:先把"被移动的行"腾出来,再按显式种子/最小空闲位落位;
        // 没出现在拖动结果里的行原座不动,落位后缺的座位补空位行——空位照占座位号,绝不压紧。
        List<TStageRosterEntry> rows = entriesOf(stageId);
        Map<Long, TStageRosterEntry> byId = rows.stream()
            .filter(r -> r.getId() != null)
            .collect(Collectors.toMap(TStageRosterEntry::getId, r -> r, (a, b) -> a));
        Map<Long, TStageRosterEntry> bySource = rows.stream()
            .filter(r -> r.getSourceCompetitorId() != null)
            .collect(Collectors.toMap(TStageRosterEntry::getSourceCompetitorId, r -> r, (a, b) -> a));
        List<TStageRosterEntry> moving = new ArrayList<>();
        List<Long> wanted = new ArrayList<>();
        List<Boolean> toHolding = new ArrayList<>();
        Set<Long> movingIds = new HashSet<>();
        for (TStageRosterOrderBo.Item it : items) {
            if (it == null) {
                continue;
            }
            TStageRosterEntry row = it.getOverrideId() != null ? byId.get(it.getOverrideId())
                : (it.getSourceCompetitorId() != null ? bySource.get(it.getSourceCompetitorId()) : null);
            if (row == null || !movingIds.add(row.getId())) {
                continue;
            }
            moving.add(row);
            wanted.add(it.getSeedRank() != null && it.getSeedRank() > 0 ? it.getSeedRank() : null);
            toHolding.add(Boolean.TRUE.equals(it.getHolding()));
        }
        // 不被移动的行占着的座位保持不动。"纯填充空位行"不占位:它是补空座用的,下面整批删掉重建,
        // 所以拖到轮空座位 = 落进这个座位(而不是被当成"已占用")。带来源引用的空位行是"移出"标记,
        // 它是实体,必须连座位一起保留。
        Set<Long> used = rows.stream()
            .filter(r -> !movingIds.contains(r.getId()))
            .filter(r -> !isPlainBye(r))
            .map(TStageRosterEntry::getSlot).filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        long maxSlot = rows.stream().map(TStageRosterEntry::getSlot).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        // 拖回"待落位区"=摘掉座位号。放在 used/maxSlot 之后做:原来占的座位照旧留在
        // 座位总数里(补成空位行),不会因为把人拿走就把整个签表缩短。
        for (int i = 0; i < moving.size(); i++) {
            if (toHolding.get(i)) {
                moving.get(i).setSlot(null);
            }
        }
        long limit = Math.max(maxSlot, moving.size());
        for (int i = 0; i < moving.size(); i++) {
            if (toHolding.get(i)) {
                continue;
            }
            Long want = wanted.get(i);
            if (want != null && want > 0 && want <= limit && used.add(want)) {
                moving.get(i).setSlot(want);
            }
        }
        long nextFree = 1L;
        for (int i = 0; i < moving.size(); i++) {
            if (toHolding.get(i)) {
                continue;
            }
            Long want = wanted.get(i);
            if (want != null && want > 0 && want <= limit
                && Objects.equals(moving.get(i).getSlot(), want)) {
                continue;
            }
            while (used.contains(nextFree)) {
                nextFree++;
            }
            moving.get(i).setSlot(nextFree);
            used.add(nextFree);
        }
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        long total = Math.max(plan, Math.max(maxSlot,
            used.stream().mapToLong(Long::longValue).max().orElse(0L)));
        // 纯填充空位行整批删掉后按缺失座位重建,保证 1..total 每个座位恰好一行
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, stageId)
            .eq(TStageRosterEntry::getSlotKind, StageConstants.SLOT_BYE)
            .isNull(TStageRosterEntry::getSourceCompetitorId));
        for (TStageRosterEntry r : moving) {
            if (r.getSlot() == null) {
                // 摘掉座位号必须显式 SET slot = NULL:updateById 默认跳过 null 字段,
                // 否则"拖回待落位区"写不进库,人还占着原座位,又和重新编号后顶上来的人撞座
                // (现场表现就是拖进去的人从名单里消失、之后再怎么拖都没反应)。
                entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                    .eq(TStageRosterEntry::getId, r.getId())
                    .set(TStageRosterEntry::getSlot, null));
                continue;
            }
            entryMapper.updateById(r);
        }
        for (long slot = 1; slot <= total; slot++) {
            if (used.contains(slot)) {
                continue;
            }
            TStageRosterEntry bye = new TStageRosterEntry();
            bye.setTournamentId(target.getTournamentId());
            bye.setTargetStageId(stageId);
            bye.setSlot(slot);
            bye.setSlotKind(StageConstants.SLOT_BYE);
            bye.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
            bye.setStatus(RosterConstants.ENTRY_STATUS_READY);
            entryMapper.insert(bye);
        }
        log.info("赛段[{}]中间层名单顺序已保存({} 项)", stageId, moving.size());
        notifyTarget(stageId);
    }

    private void validateOverrideSource(Long stageId, String op, TStageRosterOverrideBo bo) {
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)) {
            return;
        }
        if (bo.getSourceCompetitorId() == null) {
            throw new ServiceException("覆盖[{}]需要指定源赛段参赛方(sourceCompetitorId)", op);
        }
        TStage target = stageMapper.selectById(stageId);
        TCompetitor c = competitorMapper.selectById(bo.getSourceCompetitorId());
        if (c == null) {
            throw new ServiceException("源参赛方[{}]不存在", bo.getSourceCompetitorId());
        }
        // 手工名单:允许从本赛事推进链上位于目标赛段之前的任意赛段取人(不限于规则声明的来源组)
        if (!RosterConstants.OVERRIDE_REMOVE.equals(op)) {
            assertAddableSource(target, c);
        }
    }

    /** 手工加入名单的校验:源行存在、同赛事、非本赛段自身、未弃权 */
    private void assertAddableSource(TStage target, TCompetitor c) {
        if (target == null || c == null) {
            throw new ServiceException("源参赛方不存在");
        }
        TStage src = stageMapper.selectById(c.getStageId());
        if (src == null) {
            throw new ServiceException("源参赛方[{}]所属赛段不存在", c.getId());
        }
        if (Objects.equals(src.getId(), target.getId())) {
            throw new ServiceException("不能从本赛段自身手工拉人");
        }
        if (!Objects.equals(src.getTournamentId(), target.getTournamentId())) {
            throw new ServiceException("源参赛方与目标赛段不属于同一赛事");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
            throw new ServiceException("源参赛方[{}]({})已弃权,不能加入名单", c.getId(), c.getName());
        }
    }

    private void validateGuestProfile(TStage target, TStageRosterOverrideBo bo) {
        if ((bo.getPlayerId() == null || bo.getPlayerId() == 0L)
            && (bo.getGuestName() == null || bo.getGuestName().isBlank())) {
            throw new ServiceException("外卡需要提供关联选手或显示名称");
        }
        if (bo.getPlayerId() != null && bo.getPlayerId() > 0L) {
            TPlayer player = playerMapper.selectById(bo.getPlayerId());
            if (player == null || !Objects.equals(player.getTournamentId(), target.getTournamentId())) {
                throw new ServiceException("外卡关联选手不存在或不属于当前赛事");
            }
        }
    }

    private void validateSeedWithinPlan(TStage target, Long seedRank) {
        if (seedRank == null || seedRank < 1L) {
            throw new ServiceException("指定种子位需为正整数");
        }
        if (target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            && seedRank > target.getTeamCountStart()) {
            throw new ServiceException("指定种子位[{}]超出赛段计划规模[{}]",
                seedRank, target.getTeamCountStart());
        }
    }

    // ------------------------------------------------------------------
    // 来源组管理
    // ------------------------------------------------------------------

    @Override
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
        List<TStageRosterGroupBo> merged = new ArrayList<>(groupsOf(target));
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
            saveGroups(target, merged);
        }
        // 来源组变了:中间层按新规则全量重建(上游一变就全部重新来)
        rebuildEntries(stageId);
        notifyTarget(stageId);
        TStage fresh = stageMapper.selectById(stageId);
        return toVo(fresh, groupsOf(fresh), manualViewsOf(entriesOf(stageId)), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeGroup(Long stageId, Long groupId) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可删除来源组,当前: {}", target.getStatus());
        }
        if (groupId == null) {
            throw new ServiceException("请指定要删除的来源组");
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
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
        saveGroups(target, groups);
        rebuildEntries(stageId);
        log.info("赛段[{}]删除来源组[{}],剩余 {} 组", stageId, groupId, groups.size());
        notifyTarget(stageId);
    }

    @Override
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
        List<TStageRosterGroupBo> groups = groupsOf(target);
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
        saveGroups(target, groups);
        rebuildEntries(stageId);
        log.info("赛段[{}]更新来源组[{}]", stageId, groupId);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderGroups(Long stageId, List<Long> groupIds) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可调整取人顺序,当前: {}", target.getStatus());
        }
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
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
        saveGroups(target, ordered);
        rebuildEntries(stageId);
        log.info("赛段[{}]来源组取人顺序已更新:{}", stageId, groupIds);
        notifyTarget(stageId);
    }

    @Override
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
        clearEntries(stageId);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetByTarget(Long targetStageId) {
        TStage upd = new TStage();
        upd.setId(targetStageId);
        upd.setRosterApplied(0L);
        upd.setRosterSkipped(0L);
        stageMapper.updateById(upd);
        // 只撤回"已确认/已跳过"两个状态位:中间层的行(含人工调整)原样保留,
        // 它是重新确认时的起点;真要按上游重算,走 rebuildEntries(4.8)。
        notifyTarget(targetStageId);
    }

    // ------------------------------------------------------------------
    // 候选与装配(唯一写库内核)
    // ------------------------------------------------------------------

    private void assertNoPendingInSources(List<TStageRosterGroupBo> groups) {
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

    @Override
    public RosterCandidatesVo candidates(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterCandidatesVo vo = new RosterCandidatesVo();
        vo.setStageId(stageId);
        vo.setState(stateOf(stage));
        List<RosterCandidatesVo.GroupCandidates> groupsVo = new ArrayList<>();
        for (TStageRosterGroupBo g : groupsOf(stage)) {
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
            gv.setCompetitors(groupRows(g).stream()
                .map(c -> MapstructUtils.convert(c, TCompetitorVo.class))
                .toList());
            groupsVo.add(gv);
        }
        vo.setGroups(groupsVo);
        return vo;
    }

    @Override
    public boolean hasAnyCandidate(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            return false;
        }
        for (TStageRosterGroupBo g : groupsOf(stage)) {
            if (!groupRows(g).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRosterReady(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return stage != null && readyByGroups(groupsOf(stage));
    }

    @Override
    public void assertStageStartable(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null) {
            return;
        }
        List<TStageRosterGroupBo> groups = groupsOf(stage);
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
        if (!readyByGroups(groups)) {
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections) {
        TStage target = stageMapper.selectById(targetStageId);
        if (target == null) {
            throw new ServiceException("赛段不存在");
        }
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可装配名单,当前: {}", target.getStatus());
        }
        if (isLocked(target)) {
            return 0;
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
        // 装配前兜底:未配置出口时,上一赛段晋级者默认进入本赛段
        int groupCountBefore = groups.size();
        ensurePrevChainDefault(target, groups);
        if (groups.size() != groupCountBefore) {
            saveGroups(target, groups);
            // 规则刚补上:中间层必须按新规则重建,不能直接拿旧规则算出来的行去物化
            rebuildEntries(targetStageId);
        }
        boolean anyInternal = groups.stream().anyMatch(g -> g.getSourceStageId() != null);
        // 纯签到/人工名单:没有内部来源组,也没有人工加进来的行时,本赛段不带人
        boolean hasManualRows = entryMapper.selectCount(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .eq(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)) > 0;
        if (!anyInternal && !hasManualRows) {
            return 0;
        }
        long matchCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, targetStageId));
        if (matchCount > 0) {
            throw new ServiceException("赛段[{}]已生成对阵,请先重置为草稿后再确认名单", target.getName());
        }
        if (!readyByGroups(groups)) {
            throw new ServiceException("名单来源尚未全部结算,请等待后再确认名单");
        }
        assertNoPendingInSources(groups);

        List<TCompetitor> existing = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, targetStageId)
            .orderByAsc(TCompetitor::getId));
        if (Long.valueOf(1L).equals(target.getIsInitialized()) && !existing.isEmpty()) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法装配");
        }
        int plan = target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            ? target.getTeamCountStart().intValue() : 0;
        Set<String> existingPlayerKeys = memberPlayerKeys(existing);
        Map<Long, TCompetitor> existingBySource = new HashMap<>();
        for (TCompetitor c : existing) {
            if (c.getSourceCompetitorId() != null) {
                existingBySource.put(c.getSourceCompetitorId(), c);
            }
        }

        // 唯一事实:中间层的行(规则生成 + 人工调整都已落在这里)
        List<TStageRosterEntry> players = entriesOf(targetStageId).stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .toList();
        if (players.isEmpty()) {
            log.info("赛段[{}]名单装配:中间层没有可带入的人", targetStageId);
            return 0;
        }
        // 多入口汇合的人先进"待落位区"(没有座位号),必须由导播拖到真实座位上才能确认:
        // 不拦的话这些人会以 seedRank=null 物化进目标层,签表位置全空、对阵直接错。
        long unplaced = players.stream()
            .filter(e -> e.getSlot() == null || e.getSlot() < 1L)
            .count();
        if (unplaced > 0) {
            throw new ServiceException("还有 {} 人没落位(多入口汇合需要先拖到座位上),确认名单前请先在中间态把人拖到座位",
                unplaced);
        }
        if (plan > 0 && players.size() > plan) {
            throw new ServiceException("名单装配 {} 人超出赛段计划 {} 人,请先调整来源组或人工调整后再确认",
                players.size(), plan);
        }
        Set<Long> occupiedSeeds = new HashSet<>();
        for (TCompetitor c : existing) {
            if (c.getSeedRank() != null) {
                occupiedSeeds.add(c.getSeedRank());
            }
        }
        List<Long> sourceIds = players.stream().map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> sourceById = sourceIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(sourceIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        int created = 0;
        for (TStageRosterEntry e : players) {
            AssembledRow row = new AssembledRow();
            row.seedRank = e.getSlot();
            row.entryTag = e.getEntryTag();
            List<Long> playersOfRow;
            if ("GUEST".equals(e.getRefType())) {
                row.guest = true;
                row.guestName = e.getGuestName();
                row.guestPlayerId = e.getPlayerId();
                row.guestType = e.getGuestType();
                row.guestNumber = e.getGuestNumber();
                row.seedRank = e.getSlot();
                playersOfRow = e.getPlayerId() == null ? List.of() : List.of(e.getPlayerId());
            } else {
                TCompetitor src = e.getSourceCompetitorId() == null ? null : sourceById.get(e.getSourceCompetitorId());
                if (src == null || existingBySource.containsKey(src.getId())) {
                    continue;
                }
                row.source = src;
                playersOfRow = memberPlayersOf(List.of(src)).getOrDefault(src.getId(), List.of());
            }
            boolean overlap = false;
            for (Long pid : playersOfRow) {
                if (pid != null && existingPlayerKeys.contains(String.valueOf(pid))) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) {
                log.warn("赛段[{}]装配项与目标赛段已有选手重复,跳过: {}",
                    targetStageId, row.guest ? row.guestName : row.source.getName());
                continue;
            }
            if (plan > 0 && existing.size() + created >= plan) {
                throw new ServiceException("名单装配超出赛段计划 {} 人,请先调整来源组或人工调整后再确认", plan);
            }
            if (row.guest) {
                created += copyGuestIntoStage(target, row, occupiedSeeds);
            } else {
                created += copyIntoStage(target, row.source, occupiedSeeds, playersOfRow, row.seedRank);
            }
        }
        if (created > 0 || !players.isEmpty()) {
            TStage upd = new TStage();
            upd.setId(targetStageId);
            upd.setRosterApplied(1L);
            stageMapper.updateById(upd);
        }
        // 中间层的行与目标层参赛方挂钩:确认后可用于回溯"当时确认了谁"
        for (TStageRosterEntry e : players) {
            if (e.getSourceCompetitorId() == null) {
                continue;
            }
            TCompetitor created0 = competitorMapper.selectOne(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, targetStageId)
                .eq(TCompetitor::getSourceCompetitorId, e.getSourceCompetitorId())
                .last("LIMIT 1"));
            if (created0 != null && !Objects.equals(created0.getId(), e.getCompetitorId())) {
                entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                    .eq(TStageRosterEntry::getId, e.getId())
                    .set(TStageRosterEntry::getCompetitorId, created0.getId()));
            }
        }
        log.info("赛段[{}]整单装配完成:新增 {} 人(中间层 {} 人)", targetStageId, created, players.size());
        notifyTarget(targetStageId);
        return created;
    }

    private static final class AssembledRow {

        TCompetitor source;
        boolean guest;
        String guestName;
        Long guestPlayerId;
        Long guestType;
        String guestNumber;
        Long seedRank;
        String entryTag;
    }

    private static AssembledRow sourceRow(TCompetitor c) {
        AssembledRow r = new AssembledRow();
        r.source = c;
        r.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
        return r;
    }

    /**
     * 按来源组规则取人(纯规则口径):人工调整(加人/外卡/剔除/换位)不在这里参与合并——
     * 它们直接改中间层的行,只有"全量重建"这一步才会回到这里从规则重新算。
     */
    private List<AssembledRow> assembleRows(List<TStageRosterGroupBo> groups) {
        List<AssembledRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (TCompetitor c : autoCandidates(groups)) {
            if (seen.add(c.getId())) {
                rows.add(sourceRow(c));
            }
        }
        return rows;
    }

    private void assignSeeds(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds) {
        Set<Long> occupied = new HashSet<>(occupiedSeeds);
        // 淘汰赛承接上一轮淘汰赛时:晋级者按来源名次(finalRank)坐回对应座位;
        // 名次里的空洞来自"双方都轮空"的场次——把座位留空,等于让轮空也晋级到下一赛段对应的座位,
        // 下一级签表因此不会塌陷/错位(名次 = 场次 displayRow + 1,见 DownstreamRouter.markAdvance)。
        Map<Long, Boolean> knockoutSourceCache = new HashMap<>();
        for (AssembledRow r : rows) {
            Long rank = r.source == null ? null : r.source.getFinalRank();
            if (rank != null && rank >= 1L && (plan <= 0 || rank <= plan)
                && isKnockoutSource(r.source, knockoutSourceCache) && occupied.add(rank)) {
                r.seedRank = rank;
                continue;
            }
            r.seedRank = nextFreeSeed(occupied, plan);
            occupied.add(r.seedRank);
        }
    }

    /** 该来源参赛方是否来自淘汰赛赛段(只有淘汰赛的名次才对应"场次座位",含轮空留下的空洞) */
    private boolean isKnockoutSource(TCompetitor source, Map<Long, Boolean> cache) {
        if (source == null || source.getStageId() == null) {
            return false;
        }
        return cache.computeIfAbsent(source.getStageId(), id -> {
            TStage s = stageMapper.selectById(id);
            return s != null && StageModeEnum.KNOCKOUT.getCode().equals(s.getStageMode());
        });
    }

    private List<TCompetitor> autoCandidates(List<TStageRosterGroupBo> groups) {
        Map<Long, TCompetitor> merged = new LinkedHashMap<>();
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null
                || RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || RosterConstants.FILL_MANUAL.equals(g.getFillMode())) {
                continue;
            }
            List<TCompetitor> rows = new ArrayList<>(groupRows(g));
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (rotateNeeded(g, src)) {
                reorderByCircleRank(src, rows);
            }
            int quota = g.getQuota() != null && g.getQuota() > 0 ? g.getQuota() : Integer.MAX_VALUE;
            int n = 0;
            for (TCompetitor c : rows) {
                if (n++ >= quota) {
                    log.warn("来源组(源赛段 {})配额 {} 已满,剩余候选截断",
                        g.getSourceStageId(), g.getQuota());
                    break;
                }
                merged.putIfAbsent(c.getId(), c);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private boolean rotateNeeded(TStageRosterGroupBo g, TStage source) {
        if (RosterConstants.ORDER_ZONE_RANK_ROTATE.equals(g.getOrderBy())) {
            return true;
        }
        if (g.getOrderBy() != null && !g.getOrderBy().isBlank()) {
            return false;
        }
        return source != null && StageModeEnum.AUDITION.getCode().equals(source.getStageMode());
    }

    private record PartInfo(String zone, Integer row, Integer rankInMatch, BigDecimal score) {
    }

    private List<TCompetitor> groupRows(TStageRosterGroupBo g) {
        Long sourceStageId = g.getSourceStageId();
        if (sourceStageId == null) {
            return List.of();
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, sourceStageId)
            .orderByAsc(TCompetitor::getId));
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, sourceStageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        Map<Long, PartInfo> partByComp = new HashMap<>();
        if (!matchIds.isEmpty()) {
            Map<Long, String> zoneById = matches.stream().collect(Collectors.toMap(TMatch::getId,
                m -> Objects.toString(m.getDisplayZone(), ""), (a, b) -> a));
            Map<Long, Integer> rowById = matches.stream().collect(Collectors.toMap(TMatch::getId,
                m -> m.getDisplayRow() == null ? 0 : m.getDisplayRow().intValue(), (a, b) -> a));
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId))
                .forEach(p -> partByComp.putIfAbsent(p.getCompetitorId(), new PartInfo(
                    zoneById.get(p.getMatchId()),
                    rowById.get(p.getMatchId()),
                    p.getRankInMatch() == null ? null : p.getRankInMatch().intValue(),
                    p.getScoreValue())));
        }
        List<TCompetitor> groupList = new ArrayList<>();
        for (TCompetitor c : comps) {
            if (!outcomeMatches(c, g.getResultFilter())) {
                continue;
            }
            PartInfo part = partByComp.get(c.getId());
            if (!groupPass(g, c, part, zoneByIdOrder(matches))) {
                continue;
            }
            groupList.add(c);
        }
        groupList.sort(groupComparator(g, partByComp, zoneByIdOrder(matches)));
        return groupList;
    }

    private Map<String, Integer> zoneByIdOrder(List<TMatch> matches) {
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (TMatch m : matches) {
            String z = Objects.toString(m.getDisplayZone(), "");
            order.putIfAbsent(z, i++);
        }
        return order;
    }

    private boolean outcomeMatches(TCompetitor c, String filter) {
        if (filter == null || RosterConstants.FILTER_ANY.equals(filter)) {
            return !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
        }
        return filter.equals(c.getOutcomeStatus());
    }

    private boolean groupPass(TStageRosterGroupBo g, TCompetitor c, PartInfo part,
                              Map<String, Integer> zoneOrder) {
        boolean needPart = g.getZone() != null || g.getRound() != null || g.getScoreMin() != null
            || g.getScoreMax() != null || Boolean.TRUE.equals(g.getRankByZone());
        if (needPart && part == null) {
            return false;
        }
        if (g.getZone() != null && part != null
            && !Objects.equals(normalizeZone(g.getZone()), part.zone())) {
            return false;
        }
        if (g.getRound() != null && part != null && !Objects.equals(g.getRound(), part.row())) {
            return false;
        }
        if (g.getScoreMin() != null && (part == null || part.score() == null
            || part.score().compareTo(g.getScoreMin()) < 0)) {
            return false;
        }
        if (g.getScoreMax() != null && (part == null || part.score() == null
            || part.score().compareTo(g.getScoreMax()) > 0)) {
            return false;
        }
        if (Boolean.TRUE.equals(g.getRankByZone())) {
            if (part == null || part.rankInMatch() == null) {
                return false;
            }
            int r = part.rankInMatch();
            if (g.getRankStart() != null && r < g.getRankStart()) {
                return false;
            }
            if (g.getRankEnd() != null && r > g.getRankEnd()) {
                return false;
            }
        } else {
            if (g.getRankStart() != null && (c.getFinalRank() == null
                || c.getFinalRank() < g.getRankStart())) {
                return false;
            }
            if (g.getRankEnd() != null && (c.getFinalRank() == null
                || c.getFinalRank() > g.getRankEnd())) {
                return false;
            }
        }
        return true;
    }

    private String normalizeZone(String zone) {
        return zone == null ? null : (zone.startsWith("ZONE-") ? zone : zone.toUpperCase());
    }

    private Comparator<TCompetitor> groupComparator(TStageRosterGroupBo g,
                                                    Map<Long, PartInfo> partByComp,
                                                    Map<String, Integer> zoneOrder) {
        String orderBy = g.getOrderBy();
        if (RosterConstants.ORDER_SCORE.equals(orderBy)) {
            return (a, b) -> {
                BigDecimal sa = partByComp.get(a.getId()) == null ? null : partByComp.get(a.getId()).score();
                BigDecimal sb = partByComp.get(b.getId()) == null ? null : partByComp.get(b.getId()).score();
                int c = sb == null ? (sa == null ? 0 : -1) : (sa == null ? 1 : sb.compareTo(sa));
                return c != 0 ? c : Long.compare(a.getId(), b.getId());
            };
        }
        if (RosterConstants.ORDER_NUMBER.equals(orderBy)) {
            return Comparator.comparingLong((TCompetitor c) -> parseNumber(c.getNumber()))
                .thenComparing(TCompetitor::getId);
        }
        if (RosterConstants.ORDER_RANDOM.equals(orderBy)) {
            return Comparator.comparingLong((TCompetitor c) -> Long.hashCode(c.getId()))
                .thenComparing(TCompetitor::getId);
        }
        return (a, b) -> {
            if (g.getZone() != null || Boolean.TRUE.equals(g.getRankByZone())
                || RosterConstants.ORDER_ZONE_RANK.equals(orderBy)
                || RosterConstants.ORDER_ZONE_RANK_ROTATE.equals(orderBy)) {
                PartInfo pa = partByComp.get(a.getId());
                PartInfo pb = partByComp.get(b.getId());
                int za = zoneOrder.getOrDefault(pa == null ? "" : pa.zone(), Integer.MAX_VALUE);
                int zb = zoneOrder.getOrDefault(pb == null ? "" : pb.zone(), Integer.MAX_VALUE);
                if (za != zb) {
                    return Integer.compare(za, zb);
                }
                int ra = pa != null && pa.rankInMatch() != null ? pa.rankInMatch() : Integer.MAX_VALUE;
                int rb = pb != null && pb.rankInMatch() != null ? pb.rankInMatch() : Integer.MAX_VALUE;
                int c = Integer.compare(ra, rb);
                if (c != 0) {
                    return c;
                }
            } else {
                long fa = a.getFinalRank() == null ? Long.MAX_VALUE : a.getFinalRank();
                long fb = b.getFinalRank() == null ? Long.MAX_VALUE : b.getFinalRank();
                int c = Long.compare(fa, fb);
                if (c != 0) {
                    return c;
                }
            }
            return Long.compare(a.getId(), b.getId());
        };
    }

    private long parseNumber(String number) {
        if (number == null) {
            return Long.MAX_VALUE;
        }
        try {
            String n = number.trim().replaceFirst("^G", "");
            if (n.isEmpty() || !n.matches("\\d+")) {
                return Long.MAX_VALUE;
            }
            return Long.parseLong(n);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void reorderByCircleRank(TStage source, List<TCompetitor> advancers) {
        if (source == null || !StageModeEnum.AUDITION.getCode().equals(source.getStageMode())
            || advancers == null || advancers.size() < 2) {
            return;
        }
        List<TMatch> srcMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, source.getId())
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (srcMatches.size() < 2) {
            return;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(source.getRuleConfig());
        // 圈序号/名额/全局起点:与海选·排名赛结算共用同一口径(名单取人顺序必须与结算名次一致)
        Map<String, StageFlowSupport.CircleQuota> quotaCtx =
            StageFlowSupport.circleQuotaContext(source, srcMatches, "海选");
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        quotaCtx.forEach((zone, quota) -> {
            zoneOrdinal.put(zone, quota.ordinal());
            zoneBase.put(zone, quota.base());
        });
        List<Long> srcMatchIds = srcMatches.stream().map(TMatch::getId).toList();
        Map<Long, String> zoneByCompetitor = new HashMap<>();
        if (!srcMatchIds.isEmpty()) {
            Map<Long, String> matchZone = new HashMap<>();
            for (TMatch m : srcMatches) {
                matchZone.put(m.getId(), m.getDisplayZone());
            }
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, srcMatchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId)
                    .select(TMatchParticipant::getCompetitorId, TMatchParticipant::getMatchId))
                .forEach(p -> zoneByCompetitor.putIfAbsent(p.getCompetitorId(),
                    matchZone.getOrDefault(p.getMatchId(), "")));
        }
        advancers.sort((a, b) -> {
            String za = zoneByCompetitor.get(a.getId());
            String zb = zoneByCompetitor.get(b.getId());
            long fa = a.getFinalRank() == null ? Long.MAX_VALUE : a.getFinalRank();
            long fb = b.getFinalRank() == null ? Long.MAX_VALUE : b.getFinalRank();
            if (za == null || zb == null || !zoneOrdinal.containsKey(za) || !zoneOrdinal.containsKey(zb)) {
                int cmp = Long.compare(fa, fb);
                return cmp != 0 ? cmp : Long.compare(a.getId(), b.getId());
            }
            long ra = fa - zoneBase.getOrDefault(za, 0);
            long rb = fb - zoneBase.getOrDefault(zb, 0);
            if (ra != rb) {
                return Long.compare(ra, rb);
            }
            int oa = zoneOrdinal.get(za);
            int ob = zoneOrdinal.get(zb);
            if (oa != ob) {
                return Integer.compare(oa, ob);
            }
            return Long.compare(a.getId(), b.getId());
        });
    }

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
            return "第" + (order.getOrDefault(normalizeZone(g.getZone()), -1) + 1) + "圈·" + result + rank;
        }
        return (Boolean.TRUE.equals(g.getRankByZone()) ? "每圈" : "全场") + result + rank;
    }

    private Map<String, Integer> zoneOrderOf(Long sourceStageId) {
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, sourceStageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        return zoneByIdOrder(matches);
    }

    /**
     * 赛段参赛选手:名单已物化读目标层(真实参赛方),未物化读中间层名单。
     * 两个分支都按"这个赛段有哪些人"取,不掺晋级/淘汰的业务判断;未落位的人照样返回,
     * 只是 {@code seedRank=null, holding=true}。
     */
    @Override
    public StageParticipantsVo listStageParticipants(Long stageId) {
        TStage stage = stageId == null ? null : stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        StageParticipantsVo vo = new StageParticipantsVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStageMode(stage.getStageMode());
        vo.setStatus(stage.getStatus());
        int plan = stage.getTeamCountStart() == null || stage.getTeamCountStart() <= 0
            ? 0 : stage.getTeamCountStart().intValue();
        vo.setCapacity(plan);

        // 已确认晋级:名单已经物化成真实参赛方,直接读目标层
        if (isApplied(stage)) {
            vo.setSource("COMPETITOR");
            List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .orderByAsc(TCompetitor::getSeedRank)
                .orderByAsc(TCompetitor::getId));
            Map<Long, String> avatars = avatarMapOf(comps.stream()
                .map(TCompetitor::getId).filter(Objects::nonNull).toList());
            int seated = 0;
            for (TCompetitor c : comps) {
                if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                    continue;   // 退赛的不上大屏,与名单预览同一口径
                }
                StageParticipantsVo.Participant p = new StageParticipantsVo.Participant();
                p.setName(c.getName());
                p.setNumber(c.getNumber());
                p.setType(c.getType());
                p.setSeedRank(c.getSeedRank());
                p.setHolding(c.getSeedRank() == null);
                p.setEntryTag(c.getEntryTag());
                p.setRefType(c.getSourceCompetitorId() != null ? "SOURCE" : "GUEST");
                p.setSourceStageId(c.getSourceStageId());
                p.setOutcomeStatus(c.getOutcomeStatus());
                p.setAvatar(avatars.get(c.getId()));
                vo.getItems().add(p);
                if (c.getSeedRank() != null) {
                    seated++;
                }
            }
            vo.setSeatedCount(seated);
            vo.setHoldingCount(vo.getItems().size() - seated);
            return vo;
        }

        // 尚未确认:读中间层名单(含还没落位的人),大屏不空白
        vo.setSource("ROSTER");
        List<TStageRosterEntry> entries = entriesOf(stageId);
        List<Long> sourceIds = entries.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> sourceById = sourceIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(sourceIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        Map<Long, String> avatars = avatarMapOf(sourceIds);
        List<StageParticipantsVo.Participant> seatedItems = new ArrayList<>();
        List<StageParticipantsVo.Participant> holdingItems = new ArrayList<>();
        for (TStageRosterEntry e : entries) {
            if (!StageConstants.SLOT_PLAYER.equals(e.getSlotKind())) {
                continue;
            }
            TCompetitor src = e.getSourceCompetitorId() == null ? null
                : sourceById.get(e.getSourceCompetitorId());
            StageParticipantsVo.Participant p = new StageParticipantsVo.Participant();
            p.setSeedRank(e.getSlot());
            p.setHolding(e.getSlot() == null);
            p.setEntryTag(e.getEntryTag());
            p.setRefType(e.getRefType());
            if ("GUEST".equals(e.getRefType())) {
                p.setName(e.getGuestName());
                p.setNumber(e.getGuestNumber());
                p.setType(e.getGuestType());
                p.setAvatar(playerAvatar(e.getPlayerId()));
            } else {
                p.setName(src == null ? null : src.getName());
                p.setNumber(src == null ? null : src.getNumber());
                p.setType(src == null ? null : src.getType());
                p.setSourceStageId(e.getSourceStageId());
                p.setOutcomeStatus(src == null ? null : src.getOutcomeStatus());
                p.setAvatar(e.getSourceCompetitorId() == null ? null : avatars.get(e.getSourceCompetitorId()));
            }
            if (p.getHolding()) {
                holdingItems.add(p);
            } else {
                seatedItems.add(p);
            }
        }
        seatedItems.sort(Comparator.comparing(StageParticipantsVo.Participant::getSeedRank,
            Comparator.nullsLast(Comparator.naturalOrder())));
        vo.getItems().addAll(seatedItems);
        vo.getItems().addAll(holdingItems);
        vo.setSeatedCount(seatedItems.size());
        vo.setHoldingCount(holdingItems.size());
        return vo;
    }

    /** 参赛方头像表:competitor → member → player.avatar(与赛段总览/对阵同一口径) */
    private Map<Long, String> avatarMapOf(Collection<Long> competitorIds) {
        Map<Long, String> out = new HashMap<>();
        if (competitorIds == null || competitorIds.isEmpty()) {
            return out;
        }
        List<TCompetitorMember> members = competitorMemberMapper.selectList(
            Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, competitorIds));
        if (members.isEmpty()) {
            return out;
        }
        List<Long> playerIds = members.stream().map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> avatarByPlayer = new HashMap<>();
        if (!playerIds.isEmpty()) {
            playerMapper.selectList(Wrappers.<TPlayer>lambdaQuery().in(TPlayer::getId, playerIds))
                .forEach(pl -> {
                    if (pl.getAvatar() != null && !pl.getAvatar().isBlank()) {
                        avatarByPlayer.put(pl.getId(), pl.getAvatar());
                    }
                });
        }
        for (TCompetitorMember m : members) {
            String av = m.getPlayerId() == null ? null : avatarByPlayer.get(m.getPlayerId());
            if (av != null && m.getCompetitorId() != null) {
                out.putIfAbsent(m.getCompetitorId(), av);
            }
        }
        return out;
    }

    /** 单个选手头像(外卡行没有参赛方,只能按 playerId 取) */
    private String playerAvatar(Long playerId) {
        if (playerId == null) {
            return null;
        }
        TPlayer pl = playerMapper.selectById(playerId);
        return pl == null ? null : pl.getAvatar();
    }

    @Override
    public RosterPreviewVo previewAssembled(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterPreviewVo vo = new RosterPreviewVo();
        vo.setStageId(stageId);
        vo.setTargetStageId(stageId);
        List<TStageRosterGroupBo> groups = groupsOf(stage);
        vo.setReady(readyByGroups(groups));
        vo.setApplied(isApplied(stage));
        vo.setSkipped(isSkipped(stage));
        int plan = stage.getTeamCountStart() == null || stage.getTeamCountStart() <= 0
            ? 0 : stage.getTeamCountStart().intValue();
        vo.setCapacity(plan);
        // 已确认:直接展示落库的名单快照(种子位/来源标签都是真实值);
        // 不能再按规则重算——快照行已占满种子位,重算会把所有人的次序算成计划外
        if (isApplied(stage)) {
            List<TCompetitor> snapshot = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .orderByAsc(TCompetitor::getSeedRank)
                .orderByAsc(TCompetitor::getId));
            for (TCompetitor c : snapshot) {
                if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                    continue;
                }
                RosterPreviewItemVo item = new RosterPreviewItemVo();
                item.setSeedRank(c.getSeedRank());
                item.setEntryTag(c.getEntryTag());
                item.setName(c.getName());
                item.setType(c.getType());
                item.setNumber(c.getNumber());
                item.setOutcomeStatus(c.getOutcomeStatus());
                item.setFinalRank(c.getFinalRank());
                if (c.getSourceCompetitorId() != null) {
                    item.setRefType("SOURCE");
                    item.setSourceCompetitorId(c.getSourceCompetitorId());
                    item.setSourceStageId(c.getSourceStageId());
                } else {
                    item.setRefType("GUEST");
                }
                vo.getItems().add(item);
            }
            return vo;
        }
        // 未确认:直接读中间层——生成/重建时已经算好并落成行,读路径零计算、零重排
        List<TStageRosterEntry> entries = entriesOf(stageId);
        if (groups.stream().anyMatch(g -> RosterConstants.FILL_MANUAL.equals(g.getFillMode()))) {
            vo.getWarnings().add("含手动来源组:来源在「赛段配置 · 出口去向」里维护,"
                + "中间态只负责落位与确认(也可用「＋ 加人」人工补人)");
        }
        List<Long> sourceIds = entries.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> sourceById = sourceIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(sourceIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        long playerCount = 0;
        for (TStageRosterEntry e : entries) {
            if (!StageConstants.SLOT_PLAYER.equals(e.getSlotKind())) {
                continue; // 空位行(BYE/PENDING)不是候选人,但它的座位号照占(见 4.11)
            }
            playerCount++;
            RosterPreviewItemVo item = new RosterPreviewItemVo();
            item.setOverrideId(e.getId());
            item.setEntryTag(e.getEntryTag());
            item.setSeedRank(e.getSlot());
            if ("GUEST".equals(e.getRefType())) {
                item.setRefType("GUEST");
                item.setPlayerId(e.getPlayerId());
                item.setName(e.getGuestName());
                item.setType(e.getGuestType());
                item.setNumber(e.getGuestNumber());
            } else {
                TCompetitor src = e.getSourceCompetitorId() == null ? null
                    : sourceById.get(e.getSourceCompetitorId());
                item.setRefType("SOURCE");
                item.setSourceCompetitorId(e.getSourceCompetitorId());
                item.setSourceStageId(e.getSourceStageId());
                item.setName(src == null ? null : src.getName());
                item.setType(src == null ? null : src.getType());
                item.setNumber(src == null ? null : src.getNumber());
                item.setOutcomeStatus(src == null ? null : src.getOutcomeStatus());
                item.setFinalRank(src == null ? null : src.getFinalRank());
            }
            vo.getItems().add(item);
        }
        if (plan > 0 && playerCount > plan) {
            vo.getWarnings().add(String.format(
                "装配 %d 人超出赛段计划 %d 人,确认名单前请调整来源组或人工覆盖",
                playerCount, plan));
        }
        long unplaced = entries.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .filter(e -> e.getSlot() == null || e.getSlot() < 1L)
            .count();
        if (unplaced > 0) {
            vo.getWarnings().add(String.format(
                "还有 %d 人没落位:多入口汇合的人先进待落位区,拖到座位上后才能确认名单", unplaced));
        }
        return vo;
    }

    // ------------------------------------------------------------------
    // 物化
    // ------------------------------------------------------------------

    private int copyIntoStage(TStage target, TCompetitor source, Set<Long> occupiedSeeds,
                              List<Long> players, Long seedOverride) {
        int plan = target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            ? target.getTeamCountStart().intValue() : 0;
        long seed;
        if (seedOverride != null) {
            if (occupiedSeeds.contains(seedOverride)) {
                throw new ServiceException(
                    "种子覆盖位[{}]已被占用(已有参赛方或其他晋级者),请先在中间态调整预排位置", seedOverride);
            }
            seed = seedOverride;
        } else {
            seed = nextFreeSeed(occupiedSeeds, plan);
        }
        TCompetitor nc = new TCompetitor();
        nc.setTenantId(target.getTenantId());
        nc.setTournamentId(target.getTournamentId());
        nc.setStageId(target.getId());
        nc.setSourceCompetitorId(source.getId());
        nc.setSourceStageId(source.getStageId());
        nc.setFromRoster(1L);
        nc.setEntryTag(OutcomeStatusEnum.ADVANCE.getCode().equals(source.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE);
        nc.setType(source.getType());
        nc.setName(source.getName());
        nc.setNumber(source.getNumber());
        nc.setSeedRank(seed);
        nc.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        nc.setRemark(source.getRemark());
        competitorMapper.insert(nc);
        for (TCompetitorMember sm : competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, source.getId()))) {
            TCompetitorMember nm = new TCompetitorMember();
            nm.setTenantId(nc.getTenantId());
            nm.setTournamentId(nc.getTournamentId());
            nm.setCompetitorId(nc.getId());
            nm.setPlayerId(sm.getPlayerId());
            nm.setRole(sm.getRole());
            nm.setRemark(sm.getRemark());
            competitorMemberMapper.insert(nm);
        }
        occupiedSeeds.add(seed);
        return 1;
    }

    private int copyGuestIntoStage(TStage target, AssembledRow row, Set<Long> occupiedSeeds) {
        String number = row.guestNumber != null && !row.guestNumber.isBlank()
            ? row.guestNumber : nextGuestNumber(target);
        TCompetitor nc = new TCompetitor();
        nc.setTenantId(target.getTenantId());
        nc.setTournamentId(target.getTournamentId());
        nc.setStageId(target.getId());
        nc.setFromRoster(1L);
        nc.setEntryTag(RosterConstants.ENTRY_GUEST);
        nc.setType(row.guestType == null ? 0L : row.guestType);
        nc.setName(row.guestName);
        nc.setNumber(number);
        nc.setSeedRank(row.seedRank);
        nc.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(nc);
        if (row.guestPlayerId != null) {
            TCompetitorMember nm = new TCompetitorMember();
            nm.setTenantId(target.getTenantId());
            nm.setTournamentId(target.getTournamentId());
            nm.setCompetitorId(nc.getId());
            nm.setPlayerId(row.guestPlayerId);
            nm.setRole("MEMBER");
            competitorMemberMapper.insert(nm);
        }
        occupiedSeeds.add(row.seedRank);
        log.info("赛段[{}]物化外卡[{}](id={}, 号={}, 种子={})",
            target.getId(), row.guestName, nc.getId(), number, row.seedRank);
        return 1;
    }

    private String nextGuestNumber(TStage stage) {
        List<String> numbers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getNumber))
            .stream().map(TCompetitor::getNumber).filter(Objects::nonNull).toList();
        long maxNum = 0L;
        for (String n : numbers) {
            if (n.matches("\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n));
            } else if (n.matches("G\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n.substring(1)));
            }
        }
        return "G" + (maxNum + 1);
    }

    private long nextFreeSeed(Set<Long> occupiedSeeds, int plan) {
        if (plan > 0) {
            for (int s = 1; s <= plan; s++) {
                if (!occupiedSeeds.contains((long) s)) {
                    return s;
                }
            }
            return plan + 1L;
        }
        return occupiedSeeds.stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
    }

    private Set<String> memberPlayerKeys(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Set.of();
        }
        List<Long> compIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds)
                .select(TCompetitorMember::getPlayerId))
            .stream()
            .map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toSet());
    }

    private Map<Long, List<Long>> memberPlayersOf(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Map.of();
        }
        List<Long> compIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds)
                .select(TCompetitorMember::getCompetitorId, TCompetitorMember::getPlayerId))
            .stream()
            .collect(Collectors.groupingBy(TCompetitorMember::getCompetitorId,
                Collectors.mapping(TCompetitorMember::getPlayerId, Collectors.toList())));
    }

    private void notifyTarget(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage != null) {
            tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        }
    }

    // ------------------------------------------------------------------
    // 中间层名单(t_stage_roster_entry):两个赛段之间唯一的一份数据
    // ------------------------------------------------------------------

    /** 读中间层当前名单:按座位号升序,含空位行(BYE/PENDING) */
    @Override
    public List<TStageRosterEntry> entriesOf(Long targetStageId) {
        if (targetStageId == null) {
            return List.of();
        }
        List<TStageRosterEntry> rows = selectEntries(targetStageId);
        if (entriesNeedRebuild(targetStageId, rows)) {
            rebuildEntries(targetStageId);
            rows = selectEntries(targetStageId);
        }
        return rows;
    }

    private List<TStageRosterEntry> selectEntries(Long targetStageId) {
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .orderByAsc(TStageRosterEntry::getSlot)
            .orderByAsc(TStageRosterEntry::getId));
    }

    /**
     * 读路径兜底:表空(首次生成)或口径过时(来源结算状态与座位类型对不上)时,按规则重建一次。
     *
     * <p>正常路径由"来源结算 / 来源组变更"事件写入(写时物化);这里只兜"表是空的"这一种情况
     * ——例如来源赛段的状态是直接改库改出来的(历史数据/工具脚本)。</p>
     *
     * <p>口径过时指:来源还没打完却把空座位写成了"轮空"(旧版本写下的行),或来源已结算
     * 却还留着"待定"。这时重建一次即可自愈,现场不会一直卡在"一半轮空一半待定"。
     * 有人的行(PLAYER)与空位口径都正常时,读路径完全不计算。</p>
     */
    private boolean entriesNeedRebuild(Long targetStageId, List<TStageRosterEntry> rows) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null || isLocked(stage) || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            return false;
        }
        List<TStageRosterGroupBo> groups = groupsOf(stage);
        if (rows.isEmpty()) {
            // 来源还没开赛:确实该是空的,等开场事件再物化
            return materializableByGroups(groups);
        }
        boolean sourceReady = readyByGroups(groups);
        return rows.stream().anyMatch(e -> !StageConstants.SLOT_PLAYER.equals(e.getSlotKind())
            && (sourceReady
                ? !StageConstants.SLOT_BYE.equals(e.getSlotKind())
                : !StageConstants.SLOT_PENDING.equals(e.getSlotKind())));
    }

    /**
     * 重建中间层名单:清空现有行 → 按来源组规则全量生成。
     *
     * <p><b>上游一变就全部重新来</b>:来源赛段重新结算、来源组增删改,都走这里——人工调整一并丢弃
     * (没有批次、没有历史版本,表里永远只有这一份)。</p>
     *
     * <p>座位数 = 下一赛段计划规模(未配置时退化为候选人数),<b>1..N 每个座位都落一行</b>:
     * 有人=PLAYER、缺人=BYE。空位必须占号,否则读路径按"有人的行"重排会让座位整体前移。</p>
     *
     * @return 是否真的重建了(赛段已开赛/来源未结算时为 false)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rebuildEntries(Long targetStageId) {
        TStage target = targetStageId == null ? null : stageMapper.selectById(targetStageId);
        if (target == null || StageConstants.STAGE_DISCARD.equals(target.getStatus())) {
            return false;
        }
        // 已生成对阵 / 已开赛:名单锁定,不做重建(沿用"开赛后名单锁定"的口径)
        long matchCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, targetStageId));
        if (matchCount > 0 || !StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            log.info("赛段[{}]已开赛(状态={}, 场次={}),跳过中间层重建", targetStageId, target.getStatus(), matchCount);
            return false;
        }
        // 已确认过:先撤回(下一赛段还没开赛,撤回安全),再重建、重新确认
        if (isApplied(target)) {
            withdrawRosterSnapshot(target);
        }
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId));
        List<TStageRosterGroupBo> groups = groupsOf(target);
        if (groups.isEmpty() || !materializableByGroups(groups)) {
            // 来源还没开赛(拿不到任何结果):留空表,等来源开赛/结算事件再来重建
            notifyTarget(targetStageId);
            return false;
        }
        // 来源已开赛但还没全部结算:行先建出来(status=PENDING),让中间态/大屏实时看到已晋级的人
        boolean sourceReady = readyByGroups(groups);
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<AssembledRow> rows = assembleRows(groups);
        // 多入口汇合:不做任何自动排座 —— 全部人先落进"待落位区",由导播在中间态拖到真实座位。
        // 座位号 1..N 照样各占一行(空位实体),只是不带人。
        if (multiEntry(groups)) {
            int slots = Math.max(plan, rows.size());
            for (long slot = 1; slot <= slots; slot++) {
                entryMapper.insert(toEntry(target, slot, null, sourceReady));
            }
            for (AssembledRow r : rows) {
                entryMapper.insert(toHoldingEntry(target, r, sourceReady));
            }
            notifyTarget(targetStageId);
            log.info("赛段[{}]中间层已重建:多入口汇合,{} 个座位 + {} 人待落位",
                targetStageId, slots, rows.size());
            return true;
        }
        assignSeeds(rows, plan, new HashSet<>());
        // 座位数 = 计划规模;候选比计划多时保留超出计划的行(中间态给超编警告,确认时才拦),
        // 不能在这里静默丢人——否则"超编"这条守卫永远不会触发。
        int maxAssigned = rows.stream().map(r -> r.seedRank).filter(Objects::nonNull)
            .mapToInt(Long::intValue).max().orElse(0);
        int totalSlots = Math.max(plan, maxAssigned);
        if (totalSlots <= 0) {
            totalSlots = rows.size();
        }
        Map<Long, AssembledRow> rowBySlot = new LinkedHashMap<>();
        for (AssembledRow r : rows) {
            if (r.seedRank != null && r.seedRank >= 1 && r.seedRank <= totalSlots) {
                rowBySlot.putIfAbsent(r.seedRank, r);
            }
        }
        for (long slot = 1; slot <= totalSlots; slot++) {
            entryMapper.insert(toEntry(target, slot, rowBySlot.get(slot), sourceReady));
        }
        notifyTarget(targetStageId);
        log.info("赛段[{}]中间层名单已重建:{} 个座位,有人 {} 个",
            targetStageId, totalSlots, rowBySlot.size());
        return true;
    }

    /** 来源赛段变动后,重建所有"来源组引用了它"的下游赛段中间层 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rebuildEntriesOfDownstream(Long sourceStageId) {
        if (sourceStageId == null) {
            return 0;
        }
        TStage source = stageMapper.selectById(sourceStageId);
        if (source == null) {
            return 0;
        }
        // 谁引用了本赛段:按边表一次查出(替代逐段解析 JSON 判断)
        Set<Long> referencing = targetsReferencing(List.of(sourceStageId));
        if (referencing.isEmpty()) {
            return 0;
        }
        List<TStage> all = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, source.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .in(TStage::getId, referencing));
        int rebuilt = 0;
        for (TStage s : all) {
            if (rebuildEntries(s.getId())) {
                rebuilt++;
            }
        }
        return rebuilt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncPreAdvance(Long sourceStageId) {
        return syncPreAdvance(sourceStageId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settlePendingSeatsOfDownstream(Long sourceStageId) {
        if (sourceStageId == null) {
            return 0;
        }
        TStage source = stageMapper.selectById(sourceStageId);
        if (source == null) {
            return 0;
        }
        Set<Long> referencing = targetsReferencing(List.of(sourceStageId));
        if (referencing.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (TStage target : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, source.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .in(TStage::getId, referencing))) {
            if (Objects.equals(target.getId(), sourceStageId)) {
                continue;
            }
            // 还有来源没结算:那些座位仍是"待定",不能动
            if (!readyByGroups(groupsOf(target))) {
                continue;
            }
            List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, target.getId())
                    .select(TMatch::getId))
                .stream().map(TMatch::getId).filter(Objects::nonNull).toList();
            if (matchIds.isEmpty()) {
                continue;
            }
            changed += participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                .in(TMatchParticipant::getMatchId, matchIds)
                .isNull(TMatchParticipant::getCompetitorId)
                .eq(TMatchParticipant::getSlotKind, StageConstants.SLOT_PENDING)
                .set(TMatchParticipant::getSlotKind, StageConstants.SLOT_BYE));
        }
        if (changed > 0) {
            log.info("来源赛段[{}]结算后,下游中间/对阵里的 {} 个待定座位归一为轮空", sourceStageId, changed);
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncPreAdvance(Long sourceStageId, Collection<Long> competitorIds) {
        if (sourceStageId == null) {
            return 0;
        }
        TStage source = stageMapper.selectById(sourceStageId);
        if (source == null) {
            return 0;
        }
        // 只关注这些人:单场判完/重判时传本场参赛方,写入范围就锁死在这几行,不会碰到别的人
        Set<Long> only = competitorIds == null || competitorIds.isEmpty()
            ? null : competitorIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (only != null && only.isEmpty()) {
            return 0;
        }
        Set<Long> referencing = targetsReferencing(List.of(sourceStageId));
        if (referencing.isEmpty()) {
            return 0;
        }
        // 来源赛段"当前"已晋级且有名次的人:淘汰赛每场判完就写一个
        Map<Long, TCompetitor> advancerById = competitorMapper.selectList(
                Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, sourceStageId)
                    .in(only != null, TCompetitor::getId, only == null ? List.of() : only))
            .stream()
            .filter(c -> c.getFinalRank() != null && c.getFinalRank() >= 1L
                && OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()))
            .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        int changed = 0;
        for (TStage target : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, source.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .in(TStage::getId, referencing))) {
            if (Objects.equals(target.getId(), sourceStageId)) {
                continue;
            }
            changed += syncTargetPreAdvance(target, sourceStageId, advancerById, only);
        }
        return changed;
    }

    /**
     * 把一个目标赛段的中间层与来源赛段当前的晋级结果对齐:
     * 该坐哪就坐哪;不再晋级的(判错重判/重置)座位还原成空位。
     * 其它来源的行、以及人工加进来的行原样保留。
     *
     * <p><b>座位必须按"与整表重建同一套规则"算,不能拿来源赛段内的名次直接当目标座位号。</b>
     * {@code 名次 == 目标座位} 只在「单一淘汰赛来源、名次没有空洞」时成立。一旦下游的名单来源
     * 有多条(分圈海选按圈各配一条出口、多条分支汇合到同一赛段),两条来源的名次都从 1 开始,
     * 按名次落座就会把两个人写进同一个座位、把先坐进去的人挤掉——现场表现是中间态里少人/重复。
     * 整表重建走的是 {@link #assembleRows} + {@link #assignSeeds}(按来源组顺序排座并顺延空位),
     * 实时写入复用同一份映射,两条路径才不会算出两套座位。</p>
     */
    private int syncTargetPreAdvance(TStage target, Long sourceStageId,
                                     Map<Long, TCompetitor> advancerById, Set<Long> only) {
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            return 0;   // 目标已开赛/已作废:名单锁定
        }
        if (matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, target.getId())) > 0) {
            return 0;   // 目标已生成对阵:名单锁定
        }
        // 表还没铺开时会按规则整表物化一次(来源已开赛,已晋级的人本来就在其中)
        List<TStageRosterEntry> rows = entriesOf(target.getId());
        if (rows.isEmpty()) {
            return 0;
        }
        Map<Long, TStageRosterEntry> rowBySlot = rows.stream()
            .filter(e -> e.getSlot() != null)
            .collect(Collectors.toMap(TStageRosterEntry::getSlot, e -> e, (a, b) -> a));
        boolean sourceReady = readyByGroups(groupsOf(target));
        String rowStatus = sourceReady
            ? RosterConstants.ENTRY_STATUS_READY : RosterConstants.ENTRY_STATUS_PENDING;
        // 空座位:来源没打完是"待定",来源结算后没人来才是"轮空"(与 toEntry 同一口径)
        String emptySlotKind = sourceReady
            ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING;
        int changed = 0;
        // 多入口汇合:不做座位计算,只负责"把人放进待落位区 / 把人取出",座位由导播在中间态拖
        if (multiEntry(groupsOf(target))) {
            return syncHoldingPreAdvance(target, sourceStageId, advancerById, only, sourceReady);
        }
        // 候选行(competitorId → 组装行):与整表重建同源,座位已由 assignSeeds 算好。
        // 没人晋级时不必算,下面只会做清空。
        Map<Long, AssembledRow> expectedRows = advancerById.isEmpty()
            ? Map.of() : expectedRowsOf(target);
        // 映射里有座位还没铺开(计划规模小于名次):整表重建兜底,重建后即是对账结果
        if (expectedRows.values().stream()
            .anyMatch(e -> e.seedRank == null || !rowBySlot.containsKey(e.seedRank))) {
            return rebuildEntries(target.getId()) ? 1 : 0;
        }

        // 1) 先按"当前映射"把不该有的行还原成空位:
        //    · 是候选但座位不对(前一条来源进来后座位顺延、名次变了)→ 先摘掉,第 2 步重新落座;
        //    · 本来源已不再晋级(判错重判/重置)→ 还原成空位。
        //    这一步必须覆盖所有人的行,不能只处理"本场这一条来源":上游一变,别的来源
        //    已经坐好的座位也可能顺延;只管自己那几行,第 2 步就会把别人覆盖掉。
        //    座位本来就对的行走 continue,行 ID 保持不变(单场重判不会牵动其他人)。
        for (TStageRosterEntry row : rows) {
            Long holder = row.getSourceCompetitorId();
            if (holder == null || RosterConstants.ENTRY_ORIGIN_MANUAL.equals(row.getOrigin())) {
                continue;
            }
            AssembledRow expected = expectedRows.get(holder);
            if (expected != null) {
                if (Objects.equals(expected.seedRank, row.getSlot())) {
                    continue;   // 已经坐在规则算出来的座位:原地不动
                }
                clearRuleEntry(row, emptySlotKind, rowStatus);
                changed++;
                continue;
            }
            if (!Objects.equals(row.getSourceStageId(), sourceStageId)
                || (only != null && !only.contains(holder))) {
                continue;   // 别的来源的行、以及不在本批次范围内的人不动
            }
            clearRuleEntry(row, emptySlotKind, rowStatus);
            changed++;
        }

        // 2) 候选坐到规则算出来的座位上(上一步之后,目标座位要么空着,要么坐的就是同一个人)
        for (AssembledRow expected : expectedRows.values()) {
            TCompetitor advancer = expected.source;
            TStageRosterEntry row = rowBySlot.get(expected.seedRank);
            if (StageConstants.SLOT_PLAYER.equals(row.getSlotKind())
                && Objects.equals(row.getSourceCompetitorId(), advancer.getId())) {
                continue;   // 已经在位
            }
            if (RosterConstants.ENTRY_ORIGIN_MANUAL.equals(row.getOrigin())) {
                log.info("赛段[{}]座位[{}]原本是人工调整,按上游晋级结果覆盖", target.getId(), row.getSlot());
            }
            // 与 sourceRow 同一口径:胜者标"晋级",败者组带进来的人标"复活"
            String tag = OutcomeStatusEnum.ADVANCE.getCode().equals(advancer.getOutcomeStatus())
                ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
            // 显式 set:该座位原来是外卡/人工行时,外卡字段要一起清掉,否则会留下"有名字的源行"
            entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, row.getId())
                .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)
                .set(TStageRosterEntry::getRefType, "SOURCE")
                .set(TStageRosterEntry::getSourceCompetitorId, advancer.getId())
                .set(TStageRosterEntry::getSourceStageId, advancer.getStageId())
                .set(TStageRosterEntry::getPlayerId, null)
                .set(TStageRosterEntry::getGuestName, null)
                .set(TStageRosterEntry::getGuestNumber, null)
                .set(TStageRosterEntry::getOrigin, RosterConstants.ENTRY_ORIGIN_RULE)
                .set(TStageRosterEntry::getEntryTag, tag)
                .set(TStageRosterEntry::getStatus, rowStatus));
            // 同步内存对象:同一轮后续判断读的是这份快照,不同步会重复写或漏写
            row.setSlotKind(StageConstants.SLOT_PLAYER);
            row.setRefType("SOURCE");
            row.setSourceCompetitorId(advancer.getId());
            row.setSourceStageId(advancer.getStageId());
            row.setPlayerId(null);
            row.setGuestName(null);
            row.setGuestNumber(null);
            row.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
            row.setEntryTag(tag);
            row.setStatus(rowStatus);
            log.info("赛段[{}]中间层座位[{}]实时写入名单行[{}](来源名次 {}),来源赛段[{}]",
                target.getId(), row.getSlot(), advancer.getName(), advancer.getFinalRank(),
                sourceStageId);
            changed++;
        }
        if (changed > 0) {
            notifyTarget(target.getId());
        }
        return changed;
    }

    /**
     * 目标赛段的候选行(competitorId → 组装行,含来源参赛方与算好的座位):
     * 与 {@link #rebuildEntries} 物化时用的是同一套规则(来源组取人 → {@link #assignSeeds} 排座)。
     * 实时落座据此写入,两条路径不会再算出两套座位。
     */
    private Map<Long, AssembledRow> expectedRowsOf(TStage target) {
        Map<Long, AssembledRow> candidates = candidateRowsOf(target);
        if (candidates.isEmpty()) {
            return candidates;
        }
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<AssembledRow> rows = new ArrayList<>(candidates.values());
        assignSeeds(rows, plan, new HashSet<>());
        Map<Long, AssembledRow> byCompetitor = new LinkedHashMap<>();
        for (AssembledRow r : rows) {
            if (r.source != null && r.source.getId() != null && r.seedRank != null) {
                byCompetitor.putIfAbsent(r.source.getId(), r);
            }
        }
        return byCompetitor;
    }

    /** 目标赛段按来源组规则取到的候选(competitorId → 组装行),只取人、不排座 */
    private Map<Long, AssembledRow> candidateRowsOf(TStage target) {
        List<TStageRosterGroupBo> groups = groupsOf(target);
        if (groups.isEmpty()) {
            return Map.of();
        }
        Map<Long, AssembledRow> byCompetitor = new LinkedHashMap<>();
        for (AssembledRow r : assembleRows(groups)) {
            if (r.source != null && r.source.getId() != null) {
                byCompetitor.putIfAbsent(r.source.getId(), r);
            }
        }
        return byCompetitor;
    }

    /**
     * 多入口汇合时的实时同步:只负责"把人放进待落位区 / 把人取出",不做任何座位计算。
     *
     * <p>已经落位的人不会被挪走:导播拖好的座位要保住,不能因为另一个入口判完一场就被打回待落位区。</p>
     */
    private int syncHoldingPreAdvance(TStage target, Long sourceStageId,
                                      Map<Long, TCompetitor> advancerById, Set<Long> only,
                                      boolean sourceReady) {
        String rowStatus = sourceReady
            ? RosterConstants.ENTRY_STATUS_READY : RosterConstants.ENTRY_STATUS_PENDING;
        String emptySlotKind = sourceReady ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING;
        List<TStageRosterEntry> rows = entriesOf(target.getId());
        Map<Long, TStageRosterEntry> bySource = rows.stream()
            .filter(r -> r.getSourceCompetitorId() != null)
            .collect(Collectors.toMap(TStageRosterEntry::getSourceCompetitorId, r -> r, (a, b) -> a));
        // 本目标赛段的候选(全部来源的并集);advancerById 为空表示"这次改动后本来源没人晋级",
        // 不必再算候选集,下面的清理会把这批人全部取出。
        Map<Long, AssembledRow> candidates = advancerById.isEmpty()
            ? Map.of() : candidateRowsOf(target);
        int changed = 0;
        // 1) 本来源已不在这份名单里的(判错重判/重置)→ 取出:待落位行删掉,占座行还原成空位
        for (TStageRosterEntry row : rows) {
            Long holder = row.getSourceCompetitorId();
            if (holder == null
                || RosterConstants.ENTRY_ORIGIN_MANUAL.equals(row.getOrigin())
                || !Objects.equals(row.getSourceStageId(), sourceStageId)
                || (only != null && !only.contains(holder))
                || candidates.containsKey(holder)) {
                continue;
            }
            if (row.getSlot() == null) {
                // 待落位行不占座位,留着就是幽灵行,直接删
                entryMapper.deleteById(row.getId());
            } else {
                clearRuleEntry(row, emptySlotKind, rowStatus);
            }
            changed++;
        }
        // 2) 新进来的候选 → 待落位区(没有座位号)。已经有行的人不动:可能导播已经拖到座位上了。
        for (AssembledRow candidate : candidates.values()) {
            if (bySource.containsKey(candidate.source.getId())) {
                continue;
            }
            entryMapper.insert(toHoldingEntry(target, candidate, sourceReady));
            changed++;
        }
        if (changed > 0) {
            notifyTarget(target.getId());
            log.info("赛段[{}]待落位区同步:{} 行变更(来源赛段[{}])", target.getId(), changed, sourceStageId);
        }
        return changed;
    }

    /** 清空一行规则占座(座位实体保留,只把人/来源摘掉),并同步内存对象 */
    private void clearRuleEntry(TStageRosterEntry row, String emptySlotKind, String rowStatus) {
        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
            .eq(TStageRosterEntry::getId, row.getId())
            .set(TStageRosterEntry::getSlotKind, emptySlotKind)
            .set(TStageRosterEntry::getRefType, null)
            .set(TStageRosterEntry::getSourceCompetitorId, null)
            .set(TStageRosterEntry::getSourceStageId, null)
            .set(TStageRosterEntry::getPlayerId, null)
            .set(TStageRosterEntry::getGuestName, null)
            .set(TStageRosterEntry::getGuestNumber, null)
            .set(TStageRosterEntry::getEntryTag, null)
            .set(TStageRosterEntry::getStatus, rowStatus));
        row.setSlotKind(emptySlotKind);
        row.setRefType(null);
        row.setSourceCompetitorId(null);
        row.setSourceStageId(null);
        row.setPlayerId(null);
        row.setGuestName(null);
        row.setGuestNumber(null);
        row.setEntryTag(null);
        row.setStatus(rowStatus);
    }

    /** 清空中间层(跳过名单、删除来源引用时用) */
    private void clearEntries(Long targetStageId) {
        if (targetStageId == null) {
            return;
        }
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId));
    }

    /** 组装一行中间层数据:座位有人=PLAYER,空座=BYE(空位也是实体行) */
    private TStageRosterEntry toEntry(TStage target, long slot, AssembledRow row, boolean sourceReady) {
        TStageRosterEntry e = baseEntry(target, slot, sourceReady);
        if (row == null) {
            // 空座位是"待定"还是"轮空":上一赛段没打完 → 还会有人来(待定);
            // 来源全部结算后还没人来 → 真轮空。中间态与大屏预排必须同一口径,
            // 否则现场就是"同一场比赛,一边显示轮空、一边显示待定"。
            e.setSlotKind(sourceReady ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING);
            return e;
        }
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        fillPerson(e, row);
        return e;
    }

    /**
     * 组装一行"待落位"数据:人在名单里,但还没有座位号(多入口汇合时由导播在中间态拖到座位上)。
     * 座位行与它是两回事——座位号 1..N 各有一行空位实体,不看 slot 就区分不出"人在哪"。
     */
    private TStageRosterEntry toHoldingEntry(TStage target, AssembledRow row, boolean sourceReady) {
        TStageRosterEntry e = baseEntry(target, null, sourceReady);
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        fillPerson(e, row);
        return e;
    }

    private TStageRosterEntry baseEntry(TStage target, Long slot, boolean sourceReady) {
        TStageRosterEntry e = new TStageRosterEntry();
        e.setTournamentId(target.getTournamentId());
        e.setTargetStageId(target.getId());
        e.setSlot(slot);
        e.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
        e.setStatus(sourceReady ? RosterConstants.ENTRY_STATUS_READY : RosterConstants.ENTRY_STATUS_PENDING);
        return e;
    }

    /** 把组装行的人(来源参赛方 / 外卡)填进中间层行 */
    private void fillPerson(TStageRosterEntry e, AssembledRow row) {
        if (row.guest) {
            e.setRefType("GUEST");
            e.setPlayerId(row.guestPlayerId);
            e.setGuestName(row.guestName);
            e.setGuestType(row.guestType);
            e.setGuestNumber(row.guestNumber);
            e.setEntryTag(RosterConstants.ENTRY_GUEST);
            return;
        }
        e.setRefType("SOURCE");
        e.setSourceCompetitorId(row.source.getId());
        e.setSourceStageId(row.source.getStageId());
        e.setEntryTag(row.entryTag != null ? row.entryTag
            : (OutcomeStatusEnum.ADVANCE.getCode().equals(row.source.getOutcomeStatus())
                ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE));
    }

    /**
     * 插入一行"人工加进来的"名单行(ADD_SOURCE / ADD_GUEST / SEED 拉人共用)。
     * 座位已被空位行占着时原地替换,座位号不变。
     */
    private TStageRosterEntry insertManualRow(TStage target, Long stageId, TStageRosterOverrideBo bo, long slot) {
        TStageRosterEntry occupant = entryOfSlot(stageId, slot);
        if (occupant != null) {
            entryMapper.deleteById(occupant.getId());
        }
        TStageRosterEntry e = new TStageRosterEntry();
        e.setTournamentId(target.getTournamentId());
        e.setTargetStageId(stageId);
        e.setSlot(slot);
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        e.setOrigin(RosterConstants.ENTRY_ORIGIN_MANUAL);
        e.setStatus(RosterConstants.ENTRY_STATUS_READY);
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(bo.getOp())) {
            e.setRefType("GUEST");
            e.setPlayerId(bo.getPlayerId());
            e.setGuestName(bo.getGuestName() == null ? null : bo.getGuestName().trim());
            e.setGuestType(bo.getGuestType());
            e.setGuestNumber(bo.getGuestNumber());
            e.setEntryTag(RosterConstants.ENTRY_GUEST);
        } else {
            e.setRefType("SOURCE");
            e.setSourceCompetitorId(bo.getSourceCompetitorId());
            TCompetitor src = competitorMapper.selectById(bo.getSourceCompetitorId());
            e.setSourceStageId(src == null ? null : src.getStageId());
            e.setEntryTag(entryTagOf(bo.getSourceCompetitorId()));
        }
        e.setRemark(bo.getRemark());
        entryMapper.insert(e);
        return e;
    }

    /** 按座位号取一行(空位行也算);走 entriesOf 以确保中间层已生成(首次兜底) */
    private TStageRosterEntry entryOfSlot(Long stageId, long slot) {
        return entriesOf(stageId).stream()
            .filter(e -> e.getSlot() != null && e.getSlot() == slot)
            .findFirst().orElse(null);
    }

    /** 纯填充空位行:轮空且没有来源引用(不是"移出"标记),可整批删掉重建 */
    private static boolean isPlainBye(TStageRosterEntry e) {
        return StageConstants.SLOT_BYE.equals(e.getSlotKind()) && e.getSourceCompetitorId() == null;
    }

    /** 按来源参赛方取它在名单里的那一行(可能已被移出,是空位行) */
    private TStageRosterEntry entryOfSource(Long stageId, Long sourceCompetitorId) {
        if (sourceCompetitorId == null) {
            return null;
        }
        return entriesOf(stageId).stream()
            .filter(e -> Objects.equals(e.getSourceCompetitorId(), sourceCompetitorId))
            .findFirst().orElse(null);
    }

    private TStageRosterEntry requireEntryOfSource(Long stageId, Long sourceCompetitorId) {
        TStageRosterEntry e = entryOfSource(stageId, sourceCompetitorId);
        if (e == null) {
            throw new ServiceException("该参赛方不在当前名单里,无法调整");
        }
        return e;
    }

    /** 下一个空位:优先用空座(BYE);全满则接在最后(超编,预览会警告、确认时才拦) */
    private long nextFreeSlot(TStage target, Long stageId) {
        List<TStageRosterEntry> rows = entriesOf(stageId);
        Set<Long> filled = rows.stream()
            .filter(r -> StageConstants.SLOT_PLAYER.equals(r.getSlotKind()))
            .map(TStageRosterEntry::getSlot).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        long max = rows.stream().map(TStageRosterEntry::getSlot).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        for (long slot = 1; slot <= max; slot++) {
            if (!filled.contains(slot)) {
                return slot;
            }
        }
        return max + 1;
    }

    /**
     * 同名/同选手的外卡是否已在名单里(防重复加人)。
     *
     * <p>两个口径取并集:指定了选手就比 {@code playerId},填了名字就比 {@code guestName}
     * ——"按姓名新建选手"的路径拿不到稳定 playerId,只比 id 会漏掉同名。</p>
     */
    private boolean guestExists(Long stageId, TStageRosterOverrideBo bo) {
        Long playerId = bo.getPlayerId() != null && bo.getPlayerId() > 0L ? bo.getPlayerId() : null;
        String name = bo.getGuestName() == null ? null : bo.getGuestName().trim();
        if (playerId == null && (name == null || name.isBlank())) {
            return false;
        }
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
                .eq(TStageRosterEntry::getTargetStageId, stageId)
                .eq(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)
                .eq(TStageRosterEntry::getRefType, "GUEST"))
            .stream()
            .anyMatch(e -> (playerId != null && Objects.equals(e.getPlayerId(), playerId))
                || (name != null && !name.isBlank() && name.equals(e.getGuestName())));
    }

    /** 来源参赛方的入场性质:晋级 / 复活](其它一律按复活带进来) */
    private String entryTagOf(Long sourceCompetitorId) {
        TCompetitor src = sourceCompetitorId == null ? null : competitorMapper.selectById(sourceCompetitorId);
        return src != null && OutcomeStatusEnum.ADVANCE.getCode().equals(src.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
    }

    /** 中间层行 → 人工调整视图(兼容既有接口:id 即中间层行 id) */
    private TStageRosterOverrideVo toOverrideVo(TStageRosterEntry e) {
        TStageRosterOverrideVo vo = new TStageRosterOverrideVo();
        vo.setId(e.getId());
        vo.setTournamentId(e.getTournamentId());
        vo.setTargetStageId(e.getTargetStageId());
        vo.setSourceCompetitorId(e.getSourceCompetitorId());
        vo.setPlayerId(e.getPlayerId());
        vo.setGuestName(e.getGuestName());
        vo.setGuestType(e.getGuestType());
        vo.setGuestNumber(e.getGuestNumber());
        vo.setSeedRank(e.getSlot());
        vo.setRemark(e.getRemark());
        if (StageConstants.SLOT_BYE.equals(e.getSlotKind())) {
            vo.setOp(RosterConstants.OVERRIDE_REMOVE);
        } else if ("GUEST".equals(e.getRefType())) {
            vo.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        } else {
            vo.setOp(RosterConstants.OVERRIDE_ADD_SOURCE);
        }
        return vo;
    }

    /** 外卡档案校验用:把中间层外卡行还原成覆盖 BO */
    private TStageRosterOverrideBo guestBoOf(TStageRosterEntry e) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(RosterConstants.OVERRIDE_ADD_GUEST);
        bo.setPlayerId(e.getPlayerId());
        bo.setGuestName(e.getGuestName());
        bo.setGuestType(e.getGuestType());
        bo.setGuestNumber(e.getGuestNumber());
        return bo;
    }

    /**
     * 撤回已确认名单:清掉目标赛段"来自名单"的参赛方(含成员),并把 roster_applied 复位。
     * 只在赛段尚未开赛时调用(调用方已守卫)。
     */
    private void withdrawRosterSnapshot(TStage target) {
        List<TCompetitor> snapshot = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, target.getId())
            .eq(TCompetitor::getFromRoster, 1L));
        if (!snapshot.isEmpty()) {
            List<Long> ids = snapshot.stream().map(TCompetitor::getId).toList();
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, ids));
            competitorMapper.deleteByIds(ids);
            log.info("赛段[{}]撤回已确认名单,清掉 {} 名来自名单的参赛方", target.getId(), ids.size());
        }
        TStage upd = new TStage();
        upd.setId(target.getId());
        upd.setRosterApplied(0L);
        stageMapper.updateById(upd);
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
