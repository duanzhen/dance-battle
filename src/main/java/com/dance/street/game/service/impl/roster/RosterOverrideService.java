package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人工调整:在中间层名单上直接加人 / 移出 / 钉座位 / 拖动排序。
 *
 * <p>从 {@code TStageRosterServiceImpl} 搬出来的第二块。人工调整写的就是中间层的行
 * (origin=MANUAL 的新行,或把规则行的 slotKind 改成 BYE 表示"移出"),座位号一旦落定就读路径零计算;
 * 外卡只有"在这里手动添加"一条路径——按姓名现建 t_player,再回填 playerId。</p>
 *
 * @author duane
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterOverrideService {

    /** 按姓名新建外卡选手时写入 t_player.tags 的标签(json 列,与导入口径一致) */
    private static final String GUEST_PLAYER_TAGS = "[\"GUEST\"]";

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TPlayerMapper playerMapper;
    private final TStageRosterEntryMapper entryMapper;
    /** 来源组(出口)的存储:读、按 id 差异保存、变更广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 名单状态位与锁口径(人工调整的前提是名单未装配/已重置) */
    private final RosterGroupService rosterGroupService;

    public List<TStageRosterOverrideVo> listOverrides(Long stageId) {
        return rosterEntryStore.manualViewsOf(rosterEntryStore.entriesOf(stageId));
    }

    private TStage assertOverrideEditable(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (rosterGroupService.isLocked(stage)) {
            throw new ServiceException("名单已装配完成或已跳过,请先重置目标赛段再调整人工覆盖");
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可编辑人工覆盖,当前: {}", stage.getStatus());
        }
        // 同上:只有已物化出参赛行时,"已初始化"才代表名单真的被锁定过
        if (Long.valueOf(1L).equals(stage.getIsInitialized())
            && rosterGroupService.hasMaterializedCompetitors(stageId)) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法调整人工覆盖");
        }
        // 上一赛段还没结束:中间态只读。实时显示的是"打到这里为止已晋级的人",
        // 此时加人/拖位没有意义——后面每判完一场,名次座位都会按上游结果覆盖一次。
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(stage);
        if (!groups.isEmpty() && !rosterEntryStore.readyByGroups(groups)) {
            throw new ServiceException("上一赛段还没结束,中间态暂不能调整(能实时看到已晋级的选手,"
                + "等来源赛段结算完成后再改)");
        }
        return stage;
    }

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
        rosterGroupStore.notifyTarget(stageId);
        return rosterEntryStore.toOverrideVo(row);
    }

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
        rosterGroupStore.notifyTarget(stageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteOverride(Long stageId, Long overrideId) {
        assertOverrideEditable(stageId);
        TStageRosterEntry e = overrideId == null ? null : entryMapper.selectById(overrideId);
        if (e == null || !Objects.equals(e.getTargetStageId(), stageId)) {
            return;
        }
        applyDeleteOverride(e);
        log.info("赛段[{}]撤销人工条目[{}]", stageId, overrideId);
        rosterGroupStore.notifyTarget(stageId);
    }

    /** 批量撤销人工覆盖:行一次查回、广播一次,替代前端逐条 DELETE */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverrides(Long stageId, List<Long> overrideIds) {
        assertOverrideEditable(stageId);
        if (overrideIds == null || overrideIds.isEmpty()) {
            return;
        }
        List<Long> ids = overrideIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        for (TStageRosterEntry e : entryMapper.selectByIds(ids)) {
            if (e == null || !Objects.equals(e.getTargetStageId(), stageId)) {
                continue;
            }
            applyDeleteOverride(e);
        }
        log.info("赛段[{}]批量撤销人工条目 {}", stageId, ids);
        rosterGroupStore.notifyTarget(stageId);
    }

    /** 撤销单条人工覆盖的行级动作(还原"移出"/清回空位) */
    private void applyDeleteOverride(TStageRosterEntry e) {
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
                .set(TStageRosterEntry::getSourceGroupId, null)
                .set(TStageRosterEntry::getSourceSlot, null)
                .set(TStageRosterEntry::getPlayerId, null)
                .set(TStageRosterEntry::getGuestName, null)
                .set(TStageRosterEntry::getGuestNumber, null)
                .set(TStageRosterEntry::getEntryTag, null));
        } else {
            throw new ServiceException("该条目不是人工调整,无法撤销");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items) {
        TStage target = assertOverrideEditable(stageId);
        if (items == null || items.isEmpty()) {
            return;
        }
        // 两阶段落位:先把"被移动的行"腾出来,再按显式种子/最小空闲位落位;
        // 没出现在拖动结果里的行原座不动,落位后缺的座位补空位行——空位照占座位号,绝不压紧。
        List<TStageRosterEntry> rows = rosterEntryStore.entriesOf(stageId);
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
        rosterGroupStore.notifyTarget(stageId);
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
        return rosterEntryStore.entriesOf(stageId).stream()
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
        return rosterEntryStore.entriesOf(stageId).stream()
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
        List<TStageRosterEntry> rows = rosterEntryStore.entriesOf(stageId);
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
}
