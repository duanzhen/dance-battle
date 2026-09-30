package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
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
 * 规则({@code t_stage.roster_config_json})与状态位({@code roster_applied/roster_skipped})留在赛段行上。
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
    private final TPlayerMapper playerMapper;
    private final TournamentEventNotifier tournamentEventNotifier;
    /** 赛段链遍历的唯一入口(以 next 链为事实源) */
    private final StageChain stageChain;

    // ------------------------------------------------------------------
    // 名单 = stage 属性的读写
    // ------------------------------------------------------------------

    private List<TStageRosterGroupBo> groupsOf(TStage stage) {
        try {
            return StageRosterGroupCodec.parse(stage.getRosterConfigJson());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("赛段[{}]名单来源组配置损坏: {}", stage.getId(), e.getMessage());
        }
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

    private String groupsJson(List<TStageRosterGroupBo> groups) {
        return StageRosterGroupCodec.write(groups);
    }

    private void saveGroups(TStage stage, List<TStageRosterGroupBo> groups, Long applied, Long skipped) {
        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setRosterConfigJson(groupsJson(groups));
        upd.setRosterApplied(applied);
        upd.setRosterSkipped(skipped);
        stageMapper.updateById(upd);
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
        // 入口判定与默认来源组都以前驱为准;前驱由 next 链推导(prev 列仅展示)
        TStage prev = stageChain.prevOf(fresh);
        TStageRosterGroupBo defaultGroup = prev == null ? externalGroup() : defaultGroup(prev.getId());
        List<TStageRosterGroupBo> groups = groupsOf(fresh);
        boolean exists = groups.stream().anyMatch(g -> sameGroup(g, defaultGroup));
        if (!exists) {
            groups.add(defaultGroup);
            saveGroups(fresh, groups);
            log.info("赛段[{}]名单已写入默认来源组[{}]", stage.getId(),
                prev == null ? "签到(STREAM)" : "上一赛段·晋级·AUTO");
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
                // 自动生成的「上一赛段·晋级」默认组:只有它引用的不是当前直接前驱时才清掉。
                // (旧实现按"传进来的旧前驱"判断,导致未改链的普通保存也会误判为需要重建名单)
                if (isGeneratedDefault(g) && !Objects.equals(g.getSourceStageId(), prevId)) {
                    changed = true;
                    continue;
                }
            }
            kept.add(g);
        }
        // 只要有任意来源组引用新的直接前驱,链式衔接即成立(不要求必须是"整单晋级"默认组)
        boolean hasCurrentDefault = entry
            ? kept.stream().anyMatch(g -> RosterConstants.FILL_STREAM.equals(g.getFillMode()))
            : kept.stream().anyMatch(g -> Objects.equals(g.getSourceStageId(), prevId));
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
            // 缺链式默认组:补齐(ensureRosterForStage 内部会重建)
            ensureRosterForStage(stage);
        } else if (changed) {
            // 链式默认组还在、但来源组本身变了(例如跨级自定义出口的另一条组被摘掉):
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

    /**
     * 名单来源组是否缺失:null/空白,或已被摘空的空数组({@code {"groups":[]}})。
     *
     * <p>删除上游赛段时 {@link #removeSourceRefs} 会摘掉引用它的来源组,摘完全部后
     * JSON 变成空数组——那不是"空白",旧判空条件漏掉了这种情况,赛段会一直没有任何来源组。
     * 配置损坏时按"无需补齐"处理并告警,避免把删赛段流程整个阻断。</p>
     */
    private boolean rosterGroupsMissing(TStage stage) {
        String json = stage.getRosterConfigJson();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            return groupsOf(stage).isEmpty();
        } catch (RuntimeException e) {
            log.warn("赛段[{}]名单来源组配置损坏,跳过删除后补齐: {}", stage.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSourceRefs(Collection<Long> deletedStageIds) {
        if (deletedStageIds == null || deletedStageIds.isEmpty()) {
            return;
        }
        Set<Long> deleted = new HashSet<>(deletedStageIds);
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .isNotNull(TStage::getRosterConfigJson))) {
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

    private boolean isGeneratedDefault(TStageRosterGroupBo g) {
        return g.getSourceStageId() != null
            && (g.getFillMode() == null || RosterConstants.FILL_AUTO.equals(g.getFillMode()))
            && OutcomeStatusEnum.ADVANCE.getCode().equals(g.getResultFilter())
            && g.getZone() == null && g.getRound() == null
            && g.getRankStart() == null && g.getRankEnd() == null
            && g.getScoreMin() == null && g.getScoreMax() == null
            && !Boolean.TRUE.equals(g.getRankByZone());
    }

    private TStageRosterGroupBo externalGroup() {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(null);
        g.setResultFilter(RosterConstants.FILTER_ANY);
        g.setFillMode(RosterConstants.FILL_STREAM);
        g.setQuota(0);
        g.setPriority(RosterConstants.EXTERNAL_ROSTER_PRIORITY);
        return g;
    }

    private TStageRosterGroupBo defaultGroup(Long sourceStageId) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(0);
        g.setPriority(1);
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
        TStage prevStage = stageChain.prevOf(stage);
        Long prevId = prevStage == null ? null : prevStage.getId();
        if (prevId == null) {
            boolean hasStream = groups.stream().anyMatch(g ->
                RosterConstants.FILL_STREAM.equals(g.getFillMode())
                    || (g.getSourceStageId() == null && g.getFillMode() == null));
            if (!hasStream) {
                groups.add(externalGroup());
                log.info("入口赛段[{}]未配置来源,已补签到来源组", stage.getId());
            }
            return;
        }
        boolean hasPrevRef = groups.stream()
            .anyMatch(g -> Objects.equals(g.getSourceStageId(), prevId));
        if (!hasPrevRef) {
            groups.add(defaultGroup(prevId));
            log.info("赛段[{}]未配置出口,已自动补回链式默认衔接:上一赛段[{}]晋级者进入本赛段",
                stage.getId(), prevId);
        }
    }

    /** 名单就绪度(纯函数):全部内部来源组已结算 */
    private boolean readyByGroups(List<TStageRosterGroupBo> groups) {
        return readyByGroups(groups, null);
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
        // 全部来源组引用的来源赛段:一次取回,供就绪度判定复用
        Set<Long> sourceStageIds = new HashSet<>();
        for (TStage stage : stages) {
            for (TStageRosterGroupBo g : groupsOf(stage)) {
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
            result.put(stage.getId(), List.of(toVo(stage, groupsOf(stage),
                manualViewsOf(entriesByStage.getOrDefault(stage.getId(), List.of())), sourceStages)));
        }
        return result;
    }

    @Override
    public List<TStageRosterVo> listBySource(Long sourceStageId) {
        List<TStageRosterVo> out = new ArrayList<>();
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .isNotNull(TStage::getRosterConfigJson)
            .orderByAsc(TStage::getId))) {
            if (groupsOf(stage).stream().anyMatch(g ->
                Objects.equals(g.getSourceStageId(), sourceStageId))) {
                out.add(toVo(stage, groupsOf(stage),
                    manualViewsOf(entriesOf(stage.getId())), null));
            }
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
            vo.setPriority(first.getPriority());
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
        long limit = Math.max(maxSlot, moving.size());
        for (int i = 0; i < moving.size(); i++) {
            Long want = wanted.get(i);
            if (want != null && want > 0 && want <= limit && used.add(want)) {
                moving.get(i).setSlot(want);
            }
        }
        long nextFree = 1L;
        for (int i = 0; i < moving.size(); i++) {
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

    /** 手工加入名单的校验:源行存在、同赛事、位于目标赛段之前、未弃权 */
    private void assertAddableSource(TStage target, TCompetitor c) {
        if (target == null || c == null) {
            throw new ServiceException("源参赛方不存在");
        }
        TStage src = stageMapper.selectById(c.getStageId());
        if (src == null) {
            throw new ServiceException("源参赛方[{}]所属赛段不存在", c.getId());
        }
        assertSourceBeforeTarget(target, src);
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
            g.setPriority(bo.getPriority());
            desired.add(g);
        }
        if (desired.isEmpty()) {
            throw new ServiceException("请至少提供一条来源组规则");
        }
        List<TStageRosterGroupBo> merged = new ArrayList<>(groupsOf(target));
        int maxPriority = merged.stream()
            .map(g -> g.getPriority() == null ? 0 : g.getPriority())
            .max(Integer::compare).orElse(0);
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
            if (g.getPriority() == null) {
                g.setPriority(g.getSourceStageId() == null
                    ? RosterConstants.EXTERNAL_ROSTER_PRIORITY : ++maxPriority);
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
    public void removeGroup(Long stageId, int groupIndex) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可删除来源组,当前: {}", target.getStatus());
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            throw new ServiceException("来源组下标越界: {}", groupIndex);
        }
        if (groups.size() <= 1) {
            throw new ServiceException("名单至少需要保留一组来源;如需清空请删除整个来源");
        }
        groups.remove(groupIndex);
        // 若删掉了最后一条引用上一赛段的来源,自动补回链式默认衔接(未配置出口=默认进下一赛段)
        ensurePrevChainDefault(target, groups);
        saveGroups(target, groups);
        rebuildEntries(stageId);
        log.info("赛段[{}]删除来源组[{}],剩余 {} 组", stageId, groupIndex, groups.size());
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long stageId, int groupIndex, TStageRosterGroupBo group) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可编辑来源组,当前: {}", target.getStatus());
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
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            throw new ServiceException("来源组下标越界: {}", groupIndex);
        }
        TStageRosterGroupBo old = groups.get(groupIndex);
        if (group.getResultFilter() == null) {
            group.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        }
        if (group.getFillMode() == null) {
            group.setFillMode(RosterConstants.FILL_AUTO);
        }
        if (group.getQuota() == null) {
            group.setQuota(0);
        }
        if (group.getPriority() == null) {
            group.setPriority(old.getPriority() == null ? groupIndex + 1 : old.getPriority());
        }
        groups.set(groupIndex, group);
        // 编辑后若不再引用上一赛段,同样补回默认衔接
        ensurePrevChainDefault(target, groups);
        saveGroups(target, groups);
        rebuildEntries(stageId);
        log.info("赛段[{}]更新来源组[{}]", stageId, groupIndex);
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
            throw new ServiceException("赛段名单来源尚未全部结算,请等待来源赛段结束后再开始本赛段");
        }
        if (hasAnyCandidate(targetStageId)) {
            throw new ServiceException("赛段名单尚未确认,请先在中间态「确认名单」后再开始本赛段");
        }
        // 确无任何来源候选:本赛段不带人,直接放行(不写任何状态)
        log.info("赛段[{}]名单无来源候选,本赛段不带人,直接开赛", targetStageId);
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
            vo.getWarnings().add("含手动来源组:请选择参赛者或添加 ADD_SOURCE 覆盖后再确认名单");
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
        nc.setRemark("GUEST");
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
        ensureEntriesMaterialized(targetStageId);
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .orderByAsc(TStageRosterEntry::getSlot)
            .orderByAsc(TStageRosterEntry::getId));
    }

    /**
     * 首次生成的兜底:来源已全部结算、但中间层还是空表时补建一次。
     *
     * <p>正常路径由"来源结算 / 来源组变更"事件写入(写时物化);这里只兜"表是空的"这一种情况
     * ——例如来源赛段的状态是直接改库改出来的(历史数据/工具脚本)。表里一旦有行(含空位行),
     * 读路径就完全不计算。</p>
     */
    private void ensureEntriesMaterialized(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null || isLocked(stage)) {
            return;
        }
        if (entryMapper.selectCount(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)) > 0) {
            return;
        }
        if (!readyByGroups(groupsOf(stage))) {
            return; // 来源还没结算:确实该是空的
        }
        rebuildEntries(targetStageId);
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
        if (groups.isEmpty() || !readyByGroups(groups)) {
            // 来源还没结算:先留空表,等来源结算事件再来重建
            notifyTarget(targetStageId);
            return false;
        }
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<AssembledRow> rows = assembleRows(groups);
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
            entryMapper.insert(toEntry(target, slot, rowBySlot.get(slot)));
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
        List<TStage> all = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, source.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD));
        int rebuilt = 0;
        for (TStage s : all) {
            if (Objects.equals(s.getId(), sourceStageId)) {
                continue;
            }
            if (referencedBy(s, sourceStageId) && rebuildEntries(s.getId())) {
                rebuilt++;
            }
        }
        return rebuilt;
    }

    /** 该赛段的名单来源组是否引用了指定来源赛段(配置损坏时按"不引用"处理,不拖垮调用方) */
    private boolean referencedBy(TStage target, Long sourceStageId) {
        try {
            return groupsOf(target).stream()
                .anyMatch(g -> Objects.equals(g.getSourceStageId(), sourceStageId));
        } catch (RuntimeException e) {
            log.warn("赛段[{}]名单配置解析失败,跳过中间层重建: {}", target.getId(), e.getMessage());
            return false;
        }
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
    private TStageRosterEntry toEntry(TStage target, long slot, AssembledRow row) {
        TStageRosterEntry e = new TStageRosterEntry();
        e.setTournamentId(target.getTournamentId());
        e.setTargetStageId(target.getId());
        e.setSlot(slot);
        e.setOrigin(RosterConstants.ENTRY_ORIGIN_RULE);
        e.setStatus(RosterConstants.ENTRY_STATUS_READY);
        if (row == null) {
            e.setSlotKind(StageConstants.SLOT_BYE);
            return e;
        }
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        if (row.guest) {
            e.setRefType("GUEST");
            e.setPlayerId(row.guestPlayerId);
            e.setGuestName(row.guestName);
            e.setGuestType(row.guestType);
            e.setGuestNumber(row.guestNumber);
            e.setEntryTag(RosterConstants.ENTRY_GUEST);
            return e;
        }
        e.setRefType("SOURCE");
        e.setSourceCompetitorId(row.source.getId());
        e.setSourceStageId(row.source.getStageId());
        e.setEntryTag(row.entryTag != null ? row.entryTag
            : (OutcomeStatusEnum.ADVANCE.getCode().equals(row.source.getOutcomeStatus())
                ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE));
        return e;
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

    private void assertSourceBeforeTarget(TStage target, TStage source) {
        if (Objects.equals(source.getId(), target.getId())) {
            throw new ServiceException("来源赛段不能是目标赛段自身");
        }
        if (!Objects.equals(source.getTournamentId(), target.getTournamentId())) {
            throw new ServiceException("来源赛段与目标赛段不属于同一赛事");
        }
        Map<Long, TStage> byId = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, target.getTournamentId()))
            .stream().collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        Set<Long> visited = new HashSet<>();
        TStage cur = source;
        while (cur != null && visited.add(cur.getId())) {
            if (Objects.equals(cur.getId(), target.getId())) {
                return;
            }
            cur = cur.getNextStageId() == null ? null : byId.get(cur.getNextStageId());
        }
        throw new ServiceException("来源赛段必须位于目标赛段的推进链之前(沿 next 链不可达目标)");
    }
}
