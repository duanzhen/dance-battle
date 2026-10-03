package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中间层名单(t_stage_roster_entry)的行级读写:构建一行、清空一行、清空整表。
 *
 * <p>从 {@code TStageRosterServiceImpl} 拆出来的第一块。中间层是"两个赛段之间唯一的一份数据",
 * 这里只负责把"规则算出来的一行"落成表行,以及把行清干净;重建与同步的编排仍在上层,
 * 等这一层稳定后再并进来。</p>
 *
 * <p>口径与注释原样保留:空座位是"轮空"还是"待定"取决于名单来源是否已结算(中间态与大屏必须同一口径)。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterEntryStore {

    private final TStageRosterEntryMapper entryMapper;
    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    /** 来源组(出口)的读口径与广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 装配的规则求值 */
    private final RosterAssembler rosterAssembler;
    private final TMatchParticipantMapper participantMapper;

    /**
     * 读中间层当前名单:按座位号升序,含空位行(BYE/PENDING)。
     *
     * <p>只有"一行都没有"时才补一次物化(来源状态是直接改库/工具脚本改出来的,没走到写时物化),
     * 这是非破坏性的;<b>已有行一律不动</b>——不再做"口径过时"式清空重建,避免把现场的人工调整覆盖掉。</p>
     */
    public List<TStageRosterEntry> entriesOf(Long targetStageId) {
        if (targetStageId == null) {
            return List.of();
        }
        List<TStageRosterEntry> rows = selectEntries(targetStageId);
        if (rows.isEmpty() && materializeWhenEmpty(targetStageId)) {
            rebuildEntries(targetStageId);
            rows = selectEntries(targetStageId);
        }
        return rows;
    }

    /** 空表是否应补物化:赛段未锁定、规划中,且至少有一条来源已开赛 */
    private boolean materializeWhenEmpty(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null || isApplied(stage) || isSkipped(stage)
            || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            return false;
        }
        return materializableByGroups(rosterGroupStore.groupsOf(stage));
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
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(target);
        if (groups.isEmpty() || !materializableByGroups(groups)) {
            // 来源还没开赛(拿不到任何结果):留空表,等来源开赛/结算事件再来重建
            rosterGroupStore.notifyTarget(targetStageId);
            return false;
        }
        // 来源已开赛但还没全部结算:行先建出来(status=PENDING),让中间态/大屏实时看到已晋级的人
        boolean sourceReady = readyByGroups(groups);
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<RosterAssembler.AssembledRow> rows = rosterAssembler.assembleRows(groups);
        // 多入口汇合:不做任何自动排座 —— 全部人先落进"待落位区",由导播在中间态拖到真实座位。
        // 座位号 1..N 照样各占一行(空位实体),只是不带人。
        if (rosterAssembler.multiEntry(groups)) {
            int slots = Math.max(plan, rows.size());
            for (long slot = 1; slot <= slots; slot++) {
                entryMapper.insert(toEntry(target, slot, null, sourceReady));
            }
            for (RosterAssembler.AssembledRow r : rows) {
                entryMapper.insert(toHoldingEntry(target, r, sourceReady));
            }
            rosterGroupStore.notifyTarget(targetStageId);
            log.info("赛段[{}]中间层已重建:多入口汇合,{} 个座位 + {} 人待落位",
                targetStageId, slots, rows.size());
            return true;
        }
        rosterAssembler.assignSeeds(rows, plan, new HashSet<>());
        // 座位数 = 计划规模;候选比计划多时保留超出计划的行(中间态给超编警告,确认时才拦),
        // 不能在这里静默丢人——否则"超编"这条守卫永远不会触发。
        int maxAssigned = rows.stream().map(r -> r.seedRank).filter(Objects::nonNull)
            .mapToInt(Long::intValue).max().orElse(0);
        int totalSlots = Math.max(plan, maxAssigned);
        if (totalSlots <= 0) {
            totalSlots = rows.size();
        }
        Map<Long, RosterAssembler.AssembledRow> rowBySlot = new LinkedHashMap<>();
        for (RosterAssembler.AssembledRow r : rows) {
            if (r.seedRank != null && r.seedRank >= 1 && r.seedRank <= totalSlots) {
                rowBySlot.putIfAbsent(r.seedRank, r);
            }
        }
        for (long slot = 1; slot <= totalSlots; slot++) {
            entryMapper.insert(toEntry(target, slot, rowBySlot.get(slot), sourceReady));
        }
        rosterGroupStore.notifyTarget(targetStageId);
        log.info("赛段[{}]中间层名单已重建:{} 个座位,有人 {} 个",
            targetStageId, totalSlots, rowBySlot.size());
        return true;
    }

    /** 来源赛段变动后,重建所有"来源组引用了它"的下游赛段中间层 */
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
        Set<Long> referencing = rosterGroupStore.targetsReferencing(List.of(sourceStageId));
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

    /** 名单就绪度(纯函数):全部内部来源组已结算 */
    public boolean readyByGroups(List<TStageRosterGroupBo> groups) {
        return readyByGroups(groups, prefetchSourceStages(groups));
    }

    /** 同上,来源赛段可预取(批量路径一次取回,避免逐组 selectById) */
    public boolean readyByGroups(List<TStageRosterGroupBo> groups, Map<Long, TStage> sourceStages) {
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
     * 能否把中间层物化出来(展示口径):来源赛段<b>已开赛</b>或已结算即可,不要求全部结算。
     *
     * <p>淘汰赛每判完一场就把胜者标成 ADVANCE 并写回名次(见 {@code DownstreamRouter.markAdvance}),
     * 所以"上一个赛段晋级了谁"在赛段还在进行时就已经有数据了 —— 中间态、大屏预排要实时看到这些人,
     * 不能等整个赛段结算。未结算期间生成的行 status=PENDING 作为标记。</p>
     *
     * <p>注意:这只是<b>展示</b>门槛。"确认名单 / 开赛守卫"仍然要求
     * {@link #readyByGroups}(全部来源已结算),否则会把人还没打完的半成品名单物化进下一赛段。</p>
     */
    public boolean materializableByGroups(List<TStageRosterGroupBo> groups) {
        return materializableByGroups(groups, prefetchSourceStages(groups));
    }

    /** 同上,来源赛段可预取(批量路径一次取回,避免逐组 selectById) */
    public boolean materializableByGroups(List<TStageRosterGroupBo> groups, Map<Long, TStage> sourceStages) {
        if (groups.isEmpty()) {
            return false;
        }
        boolean hasStagedSource = false;
        for (TStageRosterGroupBo g : groups) {
            if (RosterConstants.FILL_STREAM.equals(g.getFillMode()) || g.getSourceStageId() == null) {
                continue;
            }
            hasStagedSource = true;
            TStage src = sourceStages == null
                ? stageMapper.selectById(g.getSourceStageId())
                : sourceStages.get(g.getSourceStageId());
            boolean started = src != null && (StageConstants.STAGE_GAMING.equals(src.getStatus())
                || StageConstants.STAGE_SETTLED.equals(src.getStatus()));
            if (started) {
                // 只要有一条来路已有结果就先物化:多入口汇合时,已结束的那条(如海选 1-8 直进)
                // 必须马上能在中间态看到;未开赛来源的座位先留 PENDING,等它开赛/结算再重建。
                return true;
            }
        }
        // 没有任何"来源赛段"的组(纯手动/流水)可直接物化;所有来源都还没开赛则留空表
        return !hasStagedSource;
    }

    /** 来源组引用到的来源赛段一次批量取回(替代逐组 selectById) */
    private Map<Long, TStage> prefetchSourceStages(List<TStageRosterGroupBo> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        List<Long> sourceStageIds = groups.stream()
            .map(TStageRosterGroupBo::getSourceStageId)
            .filter(Objects::nonNull).distinct().toList();
        return sourceStageIds.isEmpty() ? Map.of()
            : stageMapper.selectByIds(sourceStageIds).stream()
                .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
    }

    /** 撤回已确认名单(只在赛段尚未开赛时调用,调用方已守卫) */
    public void withdrawRosterSnapshot(TStage target) {
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

    private boolean isApplied(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterApplied());
    }

    private boolean isSkipped(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterSkipped());
    }

    /** 读原始行(不做兜底重建):按座位号升序,含空位行 */
    public List<TStageRosterEntry> selectEntries(Long targetStageId) {
        return entryMapper.selectList(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId)
            .orderByAsc(TStageRosterEntry::getSlot)
            .orderByAsc(TStageRosterEntry::getId));
    }

    /** 组装一行中间层数据:座位有人=PLAYER,空座=BYE(空位也是实体行) */
    public TStageRosterEntry toEntry(TStage target, long slot, RosterAssembler.AssembledRow row,
                                     boolean sourceReady) {
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
    public TStageRosterEntry toHoldingEntry(TStage target, RosterAssembler.AssembledRow row,
                                            boolean sourceReady) {
        TStageRosterEntry e = baseEntry(target, null, sourceReady);
        e.setSlotKind(StageConstants.SLOT_PLAYER);
        fillPerson(e, row);
        return e;
    }

    /** 清空一行规则占座(座位实体保留,只把人/来源摘掉),并同步内存对象 */
    public void clearRuleEntry(TStageRosterEntry row, String emptySlotKind, String rowStatus) {
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
    public void clearEntries(Long targetStageId) {
        if (targetStageId == null) {
            return;
        }
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, targetStageId));
    }

    /** 批量删除多个目标赛段的中间层(目标赛段被删除时用) */
    @Transactional(rollbackFor = Exception.class)
    public void removeEntriesOfTargets(Collection<Long> targetStageIds) {
        if (targetStageIds == null || targetStageIds.isEmpty()) {
            return;
        }
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .in(TStageRosterEntry::getTargetStageId, targetStageIds));
    }

    /** 中间层行 → 人工调整视图:手工加进来的(origin=MANUAL)与被移出的空位(BYE 且带来源引用) */
    public List<TStageRosterOverrideVo> manualViewsOf(List<TStageRosterEntry> entries) {
        return entries.stream()
            .filter(e -> RosterConstants.ENTRY_ORIGIN_MANUAL.equals(e.getOrigin())
                || (StageConstants.SLOT_BYE.equals(e.getSlotKind()) && e.getSourceCompetitorId() != null))
            .map(RosterEntryStore::toOverrideVo)
            .toList();
    }

    /** 中间层行 → 人工调整视图(兼容既有接口:id 即中间层行 id) */
    public static TStageRosterOverrideVo toOverrideVo(TStageRosterEntry e) {
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
    private void fillPerson(TStageRosterEntry e, RosterAssembler.AssembledRow row) {
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

    // ------------------------------------------------------------------
    // 中间层同步与预排:上游判完一场/重判/重置后的实时对账
    //
    // 中间层是"两个赛段之间唯一的一份数据",所以实时写入必须和整表重建
    //(rebuildEntries)用同一套规则算座位,否则会出现"重建一套、实时一套"的两套座位。
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public int syncPreAdvance(Long sourceStageId) {
        return syncPreAdvance(sourceStageId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public int settlePendingSeatsOfDownstream(Long sourceStageId) {
        if (sourceStageId == null) {
            return 0;
        }
        TStage source = stageMapper.selectById(sourceStageId);
        if (source == null) {
            return 0;
        }
        Set<Long> referencing = rosterGroupStore.targetsReferencing(List.of(sourceStageId));
        if (referencing.isEmpty()) {
            return 0;
        }
        List<TStage> targets = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, source.getTournamentId())
                .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
                .in(TStage::getId, referencing)).stream()
            .filter(t -> !Objects.equals(t.getId(), sourceStageId))
            .toList();
        if (targets.isEmpty()) {
            return 0;
        }
        // 批量预取:来源组 / 来源赛段 / 各下游赛段场次各一次查询,替代逐下游赛段回查
        List<Long> targetIds = targets.stream().map(TStage::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TStageRosterGroupBo>> groupsByTarget = rosterGroupStore.groupsOfTargets(targetIds);
        Map<Long, TStage> sourceStages = prefetchSourceStages(groupsByTarget.values().stream()
            .flatMap(List::stream).toList());
        Map<Long, List<Long>> matchIdsByStage = targetIds.isEmpty() ? Map.of()
            : matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .in(TMatch::getStageId, targetIds)
                    .select(TMatch::getId, TMatch::getStageId))
                .stream()
                .filter(m -> m.getStageId() != null && m.getId() != null)
                .collect(Collectors.groupingBy(TMatch::getStageId,
                    Collectors.mapping(TMatch::getId, Collectors.toList())));

        int changed = 0;
        for (TStage target : targets) {
            // 还有来源没结算:那些座位仍是"待定",不能动
            if (!readyByGroups(groupsByTarget.getOrDefault(target.getId(), List.of()), sourceStages)) {
                continue;
            }
            List<Long> matchIds = matchIdsByStage.getOrDefault(target.getId(), List.of());
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
        Set<Long> referencing = rosterGroupStore.targetsReferencing(List.of(sourceStageId));
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
     * 整表重建走的是 {@link RosterAssembler#assembleRows} + {@link RosterAssembler#assignSeeds}
     * (按来源组顺序排座并顺延空位),实时写入复用同一份映射,两条路径才不会算出两套座位。</p>
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
        // 来源组只查一次:readyByGroups / multiEntry / 候选装配都复用这份,避免同一次同步重复查 3 遍
        List<TStageRosterGroupBo> targetGroups = rosterGroupStore.groupsOf(target);
        boolean sourceReady = readyByGroups(targetGroups);
        String rowStatus = sourceReady
            ? RosterConstants.ENTRY_STATUS_READY : RosterConstants.ENTRY_STATUS_PENDING;
        // 空座位:来源没打完是"待定",来源结算后没人来才是"轮空"(与 toEntry 同一口径)
        String emptySlotKind = sourceReady
            ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING;
        int changed = 0;
        // 多入口汇合:不做座位计算,只负责"把人放进待落位区 / 把人取出",座位由导播在中间态拖
        if (rosterAssembler.multiEntry(targetGroups)) {
            return syncHoldingPreAdvance(target, sourceStageId, advancerById, only, sourceReady, targetGroups);
        }
        // 候选行(competitorId → 组装行):与整表重建同源,座位已由 assignSeeds 算好。
        // 没人晋级时不必算,下面只会做清空。
        // 快路径:单一淘汰赛来源时,晋级者座位 = 来源名次(assignSeeds 对淘汰来源的确定规则)。
        // 单场判罚只给本批(only)这几个人按名次落座/还原即可,不必装配整份候选、也不必扫描整张表——
        // 这是消除"每判一场都全量重算下游名单"这个 N+1 的关键。
        if (only != null && canUseRankSeatFastPath(targetGroups, sourceStageId)) {
            return syncRankSeatPreAdvance(target, sourceStageId, advancerById, only,
                rowStatus, emptySlotKind, rows, rowBySlot);
        }
        Map<Long, RosterAssembler.AssembledRow> expectedRows = advancerById.isEmpty()
            ? Map.of() : expectedRowsOf(target, targetGroups);
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
            RosterAssembler.AssembledRow expected = expectedRows.get(holder);
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
        for (RosterAssembler.AssembledRow expected : expectedRows.values()) {
            TCompetitor advancer = expected.source;
            TStageRosterEntry row = rowBySlot.get(expected.seedRank);
            if (StageConstants.SLOT_PLAYER.equals(row.getSlotKind())
                && Objects.equals(row.getSourceCompetitorId(), advancer.getId())) {
                continue;   // 已经在位
            }
            placeAdvancer(target, row, advancer, rowStatus, sourceStageId);
            changed++;
        }
        if (changed > 0) {
            rosterGroupStore.notifyTarget(target.getId());
        }
        return changed;
    }

    /** 把晋级者落到指定座位行(整表对账与"按名次落座"快路径共用同一套字段口径) */
    private void placeAdvancer(TStage target, TStageRosterEntry row, TCompetitor advancer,
                               String rowStatus, Long sourceStageId) {
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
            target.getId(), row.getSlot(), advancer.getName(), advancer.getFinalRank(), sourceStageId);
    }

    /**
     * 目标赛段的候选行(competitorId → 组装行,含来源参赛方与算好的座位):
     * 与 {@link #rebuildEntries} 物化时用的是同一套规则(来源组取人 → {@link RosterAssembler#assignSeeds} 排座)。
     * 实时落座据此写入,两条路径不会再算出两套座位。
     */
    private Map<Long, RosterAssembler.AssembledRow> expectedRowsOf(TStage target, List<TStageRosterGroupBo> groups) {
        Map<Long, RosterAssembler.AssembledRow> candidates = candidateRowsOf(groups);
        if (candidates.isEmpty()) {
            return candidates;
        }
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<RosterAssembler.AssembledRow> rows = new ArrayList<>(candidates.values());
        rosterAssembler.assignSeeds(rows, plan, new HashSet<>());
        Map<Long, RosterAssembler.AssembledRow> byCompetitor = new LinkedHashMap<>();
        for (RosterAssembler.AssembledRow r : rows) {
            if (r.source != null && r.source.getId() != null && r.seedRank != null) {
                byCompetitor.putIfAbsent(r.source.getId(), r);
            }
        }
        return byCompetitor;
    }

    /** 目标赛段按来源组规则取到的候选(competitorId → 组装行),只取人、不排座 */
    private Map<Long, RosterAssembler.AssembledRow> candidateRowsOf(List<TStageRosterGroupBo> groups) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        Map<Long, RosterAssembler.AssembledRow> byCompetitor = new LinkedHashMap<>();
        for (RosterAssembler.AssembledRow r : rosterAssembler.assembleRows(groups)) {
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
    /**
     * 快路径是否可用:唯一来源就是本次结算的赛段,且它是淘汰赛、按整单晋级。
     * 此时 {@code assignSeeds} 对每个晋级者的座位就是其来源名次({@code finalRank}),
     * 与其它候选无关,因此可以按人直接落座,不需要装配整份候选。
     */
    private boolean canUseRankSeatFastPath(List<TStageRosterGroupBo> groups, Long sourceStageId) {
        if (groups.size() != 1) {
            return false;
        }
        TStageRosterGroupBo g = groups.get(0);
        if (g.getSourceStageId() == null || !Objects.equals(g.getSourceStageId(), sourceStageId)
            || RosterConstants.FILL_MANUAL.equals(g.getFillMode())) {
            return false;
        }
        if (!OutcomeStatusEnum.ADVANCE.getCode().equals(g.getResultFilter())) {
            return false;
        }
        if (g.getZone() != null || g.getRankStart() != null || g.getRankEnd() != null) {
            return false;
        }
        TStage src = stageMapper.selectById(sourceStageId);
        return src != null && StageModeEnum.KNOCKOUT.getCode().equals(src.getStageMode());
    }

    /**
     * 按名次落座的快路径:只处理本批(only)这几个人——晋级者落到 {@code finalRank} 座位,
     * 不再是晋级者的还原成空位。不装配整份候选、不扫描整张表,消除单场判罚的 N+1。
     */
    private int syncRankSeatPreAdvance(TStage target, Long sourceStageId,
                                       Map<Long, TCompetitor> advancerById, Set<Long> only,
                                       String rowStatus, String emptySlotKind,
                                       List<TStageRosterEntry> rows,
                                       Map<Long, TStageRosterEntry> rowBySlot) {
        Map<Long, TStageRosterEntry> rowByHolder = new java.util.HashMap<>();
        for (TStageRosterEntry r : rows) {
            if (r.getSourceCompetitorId() != null) {
                rowByHolder.putIfAbsent(r.getSourceCompetitorId(), r);
            }
        }
        int changed = 0;
        for (Long cid : only) {
            TCompetitor advancer = advancerById.get(cid);
            TStageRosterEntry current = rowByHolder.get(cid);
            if (advancer == null || advancer.getFinalRank() == null || advancer.getFinalRank() < 1L) {
                // 本批里不再是晋级者的(重判/未晋级):还原成空位
                if (current != null && !RosterConstants.ENTRY_ORIGIN_MANUAL.equals(current.getOrigin())) {
                    clearRuleEntry(current, emptySlotKind, rowStatus);
                    changed++;
                }
                continue;
            }
            TStageRosterEntry row = rowBySlot.get(advancer.getFinalRank());
            if (row == null) {
                // 座位还没铺开(计划规模小于名次):交给整表重建
                return rebuildEntries(target.getId()) ? 1 : 0;
            }
            if (StageConstants.SLOT_PLAYER.equals(row.getSlotKind())
                && Objects.equals(row.getSourceCompetitorId(), cid)) {
                continue;   // 已在位
            }
            // 座位变了(重判/名次变化):先把旧行还原成空位
            if (current != null && current.getId() != null && !current.getId().equals(row.getId())
                && !RosterConstants.ENTRY_ORIGIN_MANUAL.equals(current.getOrigin())) {
                clearRuleEntry(current, emptySlotKind, rowStatus);
                changed++;
            }
            placeAdvancer(target, row, advancer, rowStatus, sourceStageId);
            changed++;
        }
        if (changed > 0) {
            rosterGroupStore.notifyTarget(target.getId());
        }
        return changed;
    }

    private int syncHoldingPreAdvance(TStage target, Long sourceStageId,
                                      Map<Long, TCompetitor> advancerById, Set<Long> only,
                                      boolean sourceReady, List<TStageRosterGroupBo> groups) {
        String rowStatus = sourceReady
            ? RosterConstants.ENTRY_STATUS_READY : RosterConstants.ENTRY_STATUS_PENDING;
        String emptySlotKind = sourceReady ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING;
        List<TStageRosterEntry> rows = entriesOf(target.getId());
        Map<Long, TStageRosterEntry> bySource = rows.stream()
            .filter(r -> r.getSourceCompetitorId() != null)
            .collect(Collectors.toMap(TStageRosterEntry::getSourceCompetitorId, r -> r, (a, b) -> a));
        // 本目标赛段的候选(全部来源的并集);advancerById 为空表示"这次改动后本来源没人晋级",
        // 不必再算候选集,下面的清理会把这批人全部取出。
        Map<Long, RosterAssembler.AssembledRow> candidates = advancerById.isEmpty()
            ? Map.of() : candidateRowsOf(groups);
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
        for (RosterAssembler.AssembledRow candidate : candidates.values()) {
            if (bySource.containsKey(candidate.source.getId())) {
                continue;
            }
            entryMapper.insert(toHoldingEntry(target, candidate, sourceReady));
            changed++;
        }
        if (changed > 0) {
            rosterGroupStore.notifyTarget(target.getId());
            log.info("赛段[{}]待落位区同步:{} 行变更(来源赛段[{}])", target.getId(), changed, sourceStageId);
        }
        return changed;
    }
}
