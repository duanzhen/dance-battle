package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterMoveBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.bo.TStageRosterRemoveBo;
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
import java.util.Collection;
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
    /** 投影规则求值:判断目标赛段是不是"多入口汇合"(座位全靠导播拖,不做来源锁定) */
    private final RosterAssembler rosterAssembler;
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
        // 来源是否结算不再是"整单只读"的门槛:改由 assertRowsAdjustable 按行判定——
        // 多入口汇合全放开(座位本来就靠导播拖),单入口只放开已结算来源的人。
        return stage;
    }

    /**
     * 行级门禁。
     *
     * <p><b>多入口汇合(待落位)不加锁</b>:那种赛段投影里根本没有座位号,座位全靠导播拖,
     * 来源判罚/结算时的对账也只"补人/取人"、不重排已落好的行,所以来自还没结束的来源
     * 也能先排位(人后续真的定案不了时,行会被摘走、座位还原成空位)。</p>
     *
     * <p>只有<b>单入口自动排座</b>的赛段才按来源结算加锁:它的座位是由来源名次算出来的,
     * 实时对账会按 {@code source_slot} 重新落座、来源结算时还会整表重写,现在调也会被冲掉。</p>
     */
    private void assertRowsAdjustable(TStage target, Collection<TStageRosterEntry> rows) {
        if (target != null && rosterAssembler.multiEntry(rosterGroupStore.groupsOf(target))) {
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<TStageRosterEntry> list = rows.stream().filter(Objects::nonNull).toList();
        if (list.isEmpty()) {
            return;
        }
        Set<Long> unsettled = rosterEntryStore.unsettledSourceStageIds(
            list.stream().map(TStageRosterEntry::getSourceStageId).filter(Objects::nonNull).toList());
        if (unsettled.isEmpty()) {
            return;
        }
        List<String> names = list.stream()
            .filter(r -> r.getSourceStageId() != null && unsettled.contains(r.getSourceStageId()))
            .map(r -> {
                TStage src = stageMapper.selectById(r.getSourceStageId());
                return src == null ? ("赛段#" + r.getSourceStageId()) : src.getName();
            })
            .distinct().toList();
        // 走到这里只剩"单入口自动排座"的赛段:它的座位按来源名次算,现在排也会被重新落座
        throw new ServiceException("来源赛段「{}」还没结束:本赛段按它的名次自动排座,"
            + "来自它的选手会被重新落座,等这段打完再调整", String.join("、", names));
    }

    private void assertRowAdjustable(TStage target, TStageRosterEntry row) {
        if (row == null) {
            return;
        }
        assertRowsAdjustable(target, List.of(row));
    }

    /**
     * 新增行(加人/外卡)的前置:来源边全部结算,座位才不会再被投影改写。
     *
     * <p><b>加到待落座区不受此限</b>:他不占座位号、不参与"来源名次=座位"的自动排座,
     * 来源入边还有没结束时也能先放进来,等来源全部结算、名次定案后再由导播拖到座位。</p>
     *
     * <p><b>只要还有任一上游入边没结束,加人就只能加到待落座区</b>:不管单入口自动排座还是
     * 多入口汇合,直接落座位都可能在来源结算/对账时被重排或冲掉。落到待落座区则不占座位号,
     * 等来源全部结算后再由导播拖到座位。</p>
     */
    private void assertAllSourcesSettled(TStage stage, boolean toHolding) {
        if (stage == null || toHolding) {
            return;
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(stage);
        if (groups.isEmpty()) {
            return;
        }
        if (!rosterEntryStore.readyByGroups(groups)) {
            throw new ServiceException("上游还有赛段没结束:现在加人只能先加到「待落座区」,"
                + "等来源全部结算后再拖到座位");
        }
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
        boolean placeInHolding = RosterConstants.PLACEMENT_HOLDING.equalsIgnoreCase(bo.getPlacement());
        validateOverrideSource(stageId, op, bo, placeInHolding);
        // 加人落到具体座位前,必须所有上游入边都已结算;否则只能加到「待落座区」。
        // 外卡、从别的赛段拉人、以及把移出过的人重新加回来,一视同仁。
        // 必须放在"按姓名建 t_player"等副作用之前:否则先建人再抛异常,事务回滚后
        // 调用方手里的 bo 还留着那个已经失效的 playerId,重试时报"外卡关联选手不存在"。
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)
            || RosterConstants.OVERRIDE_ADD_SOURCE.equals(op)) {
            assertAllSourcesSettled(target, placeInHolding);
        }
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
            assertRowAdjustable(target, row);
            row.setSlotKind(StageConstants.SLOT_BYE);
            row.setEntryTag(null);
            entryMapper.updateById(row);
            row = entryMapper.selectById(row.getId());
        } else if (RosterConstants.OVERRIDE_SEED.equals(op)) {
            row = entryOfSource(stageId, bo.getSourceCompetitorId());
            if (row == null) {
                // 规则没选中,但要求钉在某个座位:等价于"拉进来 + 钉座位"。
                // 该座位原本有人时,insertManualRow 会把原占位者顶到待落座区(不覆盖不删除)。
                row = insertManualRow(target, stageId, bo, bo.getSeedRank());
            } else {
                // 钉座位 = 与占位方互换(空位行也一样被换走),其他人不动
                TStageRosterEntry occupant = entryOfSlot(stageId, bo.getSeedRank());
                assertRowAdjustable(target, row);
                if (occupant != null && !Objects.equals(occupant.getId(), row.getId())) {
                    Long rowSlot = row.getSlot();
                    boolean occupantEmpty = occupant.getSourceCompetitorId() == null
                        && occupant.getPlayerId() == null
                        && (occupant.getGuestName() == null || occupant.getGuestName().isBlank());
                    if (!occupantEmpty) {
                        // 占位的人也要跟着挪,他同样得是已定案的行
                        assertRowAdjustable(target, occupant);
                    }
                    if (occupantEmpty) {
                        // 占的是空位:空位行直接删掉,人坐进来即可(与"加人占空位"同一口径)
                        entryMapper.deleteById(occupant.getId());
                    } else if (rowSlot == null) {
                        // 被钉的人原来在待落座(没有座位号):占位者要被挪回待落座。
                        // updateById 默认跳过 null 字段,必须显式 SET slot = NULL,否则占位者还占着
                        // 原座位,而钉进来的人也用这个座号 → 同一座位两行。
                        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                            .eq(TStageRosterEntry::getId, occupant.getId())
                            .set(TStageRosterEntry::getSlot, null));
                    } else {
                        occupant.setSlot(rowSlot);
                        entryMapper.updateById(occupant);
                    }
                }
                row.setSlot(bo.getSeedRank());
                entryMapper.updateById(row);
                row = entryMapper.selectById(row.getId());
            }
        } else {
            row = entryOfSource(stageId, bo.getSourceCompetitorId());
            boolean reAdd = row != null && RosterConstants.OVERRIDE_ADD_SOURCE.equals(op);
            boolean toHolding = placeInHolding;
            Long want = bo.getSeedRank() != null && bo.getSeedRank() > 0 ? bo.getSeedRank() : null;
            if (reAdd) {
                // 之前被移出过:这一行还在,恢复成人(不新增行)。
                assertRowAdjustable(target, row);
                if (toHolding) {
                    // 加到待落座区:摘掉座位号(updateById 跳过 null 字段,必须显式 SET)
                    entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                        .eq(TStageRosterEntry::getId, row.getId())
                        .set(TStageRosterEntry::getSlot, null)
                        .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)
                        .set(TStageRosterEntry::getEntryTag, entryTagOf(bo.getSourceCompetitorId())));
                } else if (want != null) {
                    // 按调用方指定的"实际座位"落位,而不是这一行原来带的座位:
                    // 替换/顶位与"新增落位"同一口径(只看 slot,不看 source_slot)。
                    row.setSlotKind(StageConstants.SLOT_PLAYER);
                    row.setEntryTag(entryTagOf(bo.getSourceCompetitorId()));
                    seatExistingRow(target, stageId, row, want,
                        RosterConstants.PLACEMENT_INSERT.equalsIgnoreCase(bo.getPlacement()));
                } else {
                    // 未指定目标座位:原地恢复(旧行为)
                    row.setSlotKind(StageConstants.SLOT_PLAYER);
                    row.setEntryTag(entryTagOf(bo.getSourceCompetitorId()));
                    entryMapper.updateById(row);
                }
            } else if (toHolding) {
                // 新增行,直接进待落座区(不占座位号,后续由导播拖到座位)
                row = insertManualRow(target, stageId, bo, null);
            } else {
                // 新增行(加外卡 / 从别的赛段手工拉人);来源是否结算的前置已在方法开头统一校验
                long slot = want != null ? want : nextFreeSlot(target, stageId);
                // INSERT:插到该座位,原占位者及后面的人整体 +1(座位号是位置,不是数组下标);
                // 插入导致超出计划规模的尾部行摘到待落座区(而不是删掉)。
                if (RosterConstants.PLACEMENT_INSERT.equalsIgnoreCase(bo.getPlacement())
                    && want != null) {
                    shiftSeatsFrom(stageId, slot, 1);
                    pushOverflowToHolding(target, stageId);
                }
                TStageRosterEntry occupant = entryOfSlot(stageId, slot);
                if (occupant != null) {
                    boolean occupantEmpty = occupant.getSourceCompetitorId() == null
                        && occupant.getPlayerId() == null
                        && (occupant.getGuestName() == null || occupant.getGuestName().isBlank());
                    if (occupantEmpty) {
                        entryMapper.deleteById(occupant.getId()); // 占的是空位:原地换人,座位号不变
                    } else {
                        // 替换:原占位者(真人)挪到待落座区(从"外面"加进来的人占他的座位)
                        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                            .eq(TStageRosterEntry::getId, occupant.getId())
                            .set(TStageRosterEntry::getSlot, null));
                    }
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
        assertRowAdjustable(target, e);
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
        TStage target = assertOverrideEditable(stageId);
        TStageRosterEntry e = overrideId == null ? null : entryMapper.selectById(overrideId);
        if (e == null || !Objects.equals(e.getTargetStageId(), stageId)) {
            return;
        }
        assertRowAdjustable(target, e);
        applyDeleteOverride(e);
        log.info("赛段[{}]撤销人工条目[{}]", stageId, overrideId);
        rosterGroupStore.notifyTarget(stageId);
    }

    /** 批量撤销人工覆盖:行一次查回、广播一次,替代前端逐条 DELETE */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverrides(Long stageId, List<Long> overrideIds) {
        TStage target = assertOverrideEditable(stageId);
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
            assertRowAdjustable(target, e);
            applyDeleteOverride(e);
        }
        log.info("赛段[{}]批量撤销人工条目 {}", stageId, ids);
        rosterGroupStore.notifyTarget(stageId);
    }

    /**
     * 移动意图:把某一行移到目标座位,或移到待落位区。
     *
     * <p>前端只说"谁移到哪",这里负责落位与占位者处理:<br>
     * 目标座位有人 → 把他换到被移动者原来的座位;被移动者原来在待落位区 → 占位者一起进待落位区。
     * 移动完补齐 1..N 的空位实体行(座位不压紧)。最新名单由门面 {@code moveRosterRow} 整份返回。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveRow(Long stageId, TStageRosterMoveBo bo) {
        TStage target = assertOverrideEditable(stageId);
        if (bo == null) {
            throw new ServiceException("请提供移动意图");
        }
        TStageRosterEntry row = bo.getOverrideId() != null
            ? entryMapper.selectById(bo.getOverrideId())
            : entryOfSource(stageId, bo.getSourceCompetitorId());
        if (row == null || !Objects.equals(row.getTargetStageId(), stageId)) {
            throw new ServiceException("要移动的名单行不存在或不属于本赛段");
        }
        assertRowAdjustable(target, row);
        Long from = row.getSlot();
        boolean toHolding = Boolean.TRUE.equals(bo.getToHolding())
            || bo.getTargetSeed() == null || bo.getTargetSeed() <= 0;
        if (toHolding) {
            if (from == null) {
                return;   // 已经在待落位区
            }
            // 摘掉座位号必须显式 SET slot = NULL(updateById 会跳过 null 字段)
            entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, row.getId())
                .set(TStageRosterEntry::getSlot, null));
        } else {
            long seed = bo.getTargetSeed();
            validateSeedWithinPlan(target, seed);
            if (Objects.equals(from, seed)) {
                return;   // 已经在目标座位
            }
            TStageRosterEntry occupant = entryOfSlot(stageId, seed);
            if (occupant != null && !Objects.equals(occupant.getId(), row.getId())) {
                entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                    .eq(TStageRosterEntry::getId, occupant.getId())
                    .set(TStageRosterEntry::getSlot, from));
            }
            var update = Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, row.getId())
                .set(TStageRosterEntry::getSlot, seed)
                .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER);
            if (row.getEntryTag() == null && row.getSourceCompetitorId() != null) {
                // 原来是"移出"标记的行被拖回座位:入场性质一并还原
                update.set(TStageRosterEntry::getEntryTag, entryTagOf(row.getSourceCompetitorId()));
            }
            entryMapper.update(null, update);
        }
        normalizeEmptySeats(target, stageId);
        rosterGroupStore.notifyTarget(stageId);
        log.info("赛段[{}]名单行[{}]移动到{}", stageId, row.getId(),
            toHolding ? "待落位区" : ("座位 " + bo.getTargetSeed()));
    }

    /** 把座位号 >= fromSlot 的行整体挪 delta(插入时 +1,压缩时 -1) */
    private void shiftSeatsFrom(Long stageId, long fromSlot, long delta) {
        // 一条集合式 UPDATE 代替逐行 updateById:插入后移/移出压缩时,
        // 逐行写法在人多时是 O(行数) 次库往返(与签到落圈的顺延同一类问题)
        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
            .eq(TStageRosterEntry::getTargetStageId, stageId)
            .isNotNull(TStageRosterEntry::getSlot)
            .ge(TStageRosterEntry::getSlot, fromSlot)
            .setSql("slot = slot + " + delta));
    }

    /**
     * 插入导致超出赛段计划规模时:把座位号 &gt; 计划规模的"尾部"行摘到待落座区(座位不压紧)。
     *
     * <p>口径:座位是位置不是数组下标——「顶位插入」多出来的是<b>最后一位</b>,他进待落座区,
     * 而不是被删掉。计划规模未知(teamCountStart≤0)时不做溢出处理(没有"末尾"可判定)。</p>
     */
    private void pushOverflowToHolding(TStage target, Long stageId) {
        long plan = target == null || target.getTeamCountStart() == null ? 0L : target.getTeamCountStart();
        if (plan <= 0) {
            return;
        }
        // updateById 跳过 null 字段,这里显式 SET slot = NULL
        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
            .eq(TStageRosterEntry::getTargetStageId, stageId)
            .isNotNull(TStageRosterEntry::getSlot)
            .gt(TStageRosterEntry::getSlot, plan)
            .set(TStageRosterEntry::getSlot, null));
    }

    /**
     * 把一行<b>已存在</b>的人落到指定座位(替换/顶位),按<b>实际座位(slot)</b>处理占位者。
     *
     * <p>INSERT=插到该座位,原占位者及后面的人整体 +1;REPLACE=与占位者互换(占位者回到本行原座位,
     * 本行原座位为空则占位者进待落座区)。与"新增落位""拖动移动"同一口径——不看来源备份的
     * {@code source_slot}。</p>
     */
    private void seatExistingRow(TStage target, Long stageId, TStageRosterEntry row, long seat, boolean insert) {
        Long from = row.getSlot();
        if (insert) {
            // 先把本行摘出来,避免"整体后移"时把自己也挪走;再后移,最后落到目标座位
            if (from != null) {
                entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                    .eq(TStageRosterEntry::getId, row.getId())
                    .set(TStageRosterEntry::getSlot, null));
                row.setSlot(null);
            }
            shiftSeatsFrom(stageId, seat, 1);
            pushOverflowToHolding(target, stageId);
            entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, row.getId())
                .set(TStageRosterEntry::getSlot, seat)
                .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)
                .set(TStageRosterEntry::getEntryTag, row.getEntryTag()));
            row.setSlot(seat);
            row.setSlotKind(StageConstants.SLOT_PLAYER);
            normalizeEmptySeats(target, stageId);
            return;
        }
        // REPLACE:与占位者互换
        if (!Objects.equals(from, seat)) {
            TStageRosterEntry occupant = entryOfSlot(stageId, seat);
            if (occupant != null && !Objects.equals(occupant.getId(), row.getId())) {
                boolean occupantEmpty = occupant.getSourceCompetitorId() == null
                    && occupant.getPlayerId() == null
                    && (occupant.getGuestName() == null || occupant.getGuestName().isBlank());
                if (occupantEmpty) {
                    // 占的是空位:原地换人,座位号不变
                    entryMapper.deleteById(occupant.getId());
                } else {
                    // 占位者挪到本行原座位(from 可为 null → 占位者进待落座区)
                    entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                        .eq(TStageRosterEntry::getId, occupant.getId())
                        .set(TStageRosterEntry::getSlot, from));
                }
            }
        }
        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
            .eq(TStageRosterEntry::getId, row.getId())
            .set(TStageRosterEntry::getSlot, seat)
            .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER)
            .set(TStageRosterEntry::getEntryTag, row.getEntryTag()));
        row.setSlot(seat);
        row.setSlotKind(StageConstants.SLOT_PLAYER);
        normalizeEmptySeats(target, stageId);
    }

    /**
     * 移出意图:把若干行从名单里拿掉,可选"后面的人整体顶上一位"(fillGap)。
     *
     * <p>规则带进来的人留一个"移出"标记(BYE 行保留来源引用,可撤销);人工/外卡行整行删掉。
     * fillGap 只对单次单行移出有意义(前端也是这么用的):删掉该座位后,后面的座位整体 -1。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeRows(Long stageId, TStageRosterRemoveBo bo) {
        TStage target = assertOverrideEditable(stageId);
        if (bo == null) {
            throw new ServiceException("请提供要移出的名单行");
        }
        Set<Long> ids = bo.getIds() == null ? Set.of()
            : bo.getIds().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> sourceIds = bo.getSourceCompetitorIds() == null ? Set.of()
            : bo.getSourceCompetitorIds().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty() && sourceIds.isEmpty()) {
            return;
        }
        List<TStageRosterEntry> targets = rosterEntryStore.entriesOf(stageId).stream()
            .filter(e -> (e.getId() != null && ids.contains(e.getId()))
                || (e.getSourceCompetitorId() != null && sourceIds.contains(e.getSourceCompetitorId())))
            .toList();
        if (targets.isEmpty()) {
            return;
        }
        assertRowsAdjustable(target, targets);
        boolean fillGap = Boolean.TRUE.equals(bo.getFillGap()) && targets.size() == 1;
        Long minSlot = targets.stream().map(TStageRosterEntry::getSlot)
            .filter(Objects::nonNull).min(Long::compareTo).orElse(null);
        for (TStageRosterEntry e : targets) {
            if (RosterConstants.ENTRY_ORIGIN_MANUAL.equals(e.getOrigin())) {
                // 人工行/外卡:整行删掉(座位由下面的补空位还原)
                entryMapper.deleteById(e.getId());
                continue;
            }
            if (fillGap) {
                // 顶上一位:这个座位整个消失(连"移出标记"也不留)
                entryMapper.deleteById(e.getId());
                continue;
            }
            // 规则带进来的人:留一个"移出"标记,可撤销
            entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                .eq(TStageRosterEntry::getId, e.getId())
                .set(TStageRosterEntry::getSlotKind, StageConstants.SLOT_BYE)
                .set(TStageRosterEntry::getEntryTag, null));
        }
        if (fillGap && minSlot != null) {
            shiftSeatsFrom(stageId, minSlot + 1, -1);
        }
        normalizeEmptySeats(target, stageId);
        rosterGroupStore.notifyTarget(stageId);
        log.info("赛段[{}]移出 {} 行{}", stageId, targets.size(), fillGap ? "(后面的人顶上一位)" : "");
    }

    private void normalizeEmptySeats(TStage target, Long stageId) {
        List<TStageRosterEntry> rows = rosterEntryStore.entriesOf(stageId);
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        long maxSlot = rows.stream().map(TStageRosterEntry::getSlot).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        long total = Math.max(plan, maxSlot);
        Set<Long> used = rows.stream()
            .filter(r -> r.getSlot() != null && !isPlainBye(r))
            .map(TStageRosterEntry::getSlot)
            .collect(Collectors.toSet());
        entryMapper.delete(Wrappers.<TStageRosterEntry>lambdaQuery()
            .eq(TStageRosterEntry::getTargetStageId, stageId)
            .eq(TStageRosterEntry::getSlotKind, StageConstants.SLOT_BYE)
            .isNull(TStageRosterEntry::getSourceCompetitorId));
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
        // 行级门禁:单入口自动排座的赛段里,来自"未结算来源"的行不能动。前端每次都是整单提交
        // (未变的行也在里面),所以这里不是"收到就拦",而是把这些行直接从可移动集合里剔出去——
        // 它们照旧占着座位(下面进 used),别的行也就顶不掉它们;等来源结算后再拖即可。
        // 多入口汇合不加锁:那类赛段的座位本来就是导播自己排的(与 moveRow 同一口径)。
        Set<Long> lockedRowIds = new HashSet<>();
        if (!rosterAssembler.multiEntry(rosterGroupStore.groupsOf(target))) {
            Set<Long> unsettledSources = rosterEntryStore.unsettledSourceStageIds(
                rows.stream().map(TStageRosterEntry::getSourceStageId).filter(Objects::nonNull).toList());
            rows.stream()
                .filter(r -> r.getSourceStageId() != null && unsettledSources.contains(r.getSourceStageId()))
                .map(TStageRosterEntry::getId)
                .forEach(lockedRowIds::add);
        }
        for (TStageRosterOrderBo.Item it : items) {
            if (it == null) {
                continue;
            }
            TStageRosterEntry row = it.getOverrideId() != null ? byId.get(it.getOverrideId())
                : (it.getSourceCompetitorId() != null ? bySource.get(it.getSourceCompetitorId()) : null);
            if (row == null || lockedRowIds.contains(row.getId()) || !movingIds.add(row.getId())) {
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

    private void validateOverrideSource(Long stageId, String op, TStageRosterOverrideBo bo,
                                        boolean toHolding) {
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
            assertAddableSource(target, c, toHolding);
        }
    }

    /**
     * 手工加入名单的校验:源行存在、同赛事、非本赛段自身、未弃权。
     *
     * <p>只是先把人拉进「待落座区」({@code toHolding})时不要求该来源已结算:待落座不占座位号,
     * 来源还没打完也能先安排进来,等定案后再由导播拖到座位。直接落座位则要求该来源已结算。</p>
     */
    private void assertAddableSource(TStage target, TCompetitor c, boolean toHolding) {
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
        // 直接落座位要求该来源已结算(加人的统一门禁在 addOverride,这里再兜一道"具体来源")。
        if (!toHolding && !StageConstants.STAGE_SETTLED.equals(src.getStatus())) {
            throw new ServiceException("来源赛段「{}」还没结束:现在只能先加到「待落座区」,"
                + "等它结算后再拖到座位", src.getName());
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
     * 目标座位原本有人时,原占位者顶到待落座区(不覆盖、不删除),座位号让给新行。
     */
    private TStageRosterEntry insertManualRow(TStage target, Long stageId, TStageRosterOverrideBo bo, Long slot) {
        if (slot != null) {
            parkSeatOccupantToHolding(stageId, slot);
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

    /**
     * 目标座位上如果有人,把人挪到待落座区(摘座位号,不删除也不覆盖);纯空位行直接删掉。
     *
     * <p>单入口自动排座的赛段座位是算出来的:导播手工加进来的人坐在上面时,来源一变就会
     * 被重新落座。这里先把人顶到待落座区,座位让给要坐的人 —— 人不会丢。</p>
     */
    private void parkSeatOccupantToHolding(Long stageId, long slot) {
        TStageRosterEntry occupant = entryOfSlot(stageId, slot);
        if (occupant == null) {
            return;
        }
        boolean empty = occupant.getSourceCompetitorId() == null
            && occupant.getPlayerId() == null
            && (occupant.getGuestName() == null || occupant.getGuestName().isBlank());
        if (empty) {
            entryMapper.deleteById(occupant.getId());
            return;
        }
        // updateById 跳过 null 字段,必须显式 SET slot = NULL
        entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
            .eq(TStageRosterEntry::getId, occupant.getId())
            .set(TStageRosterEntry::getSlot, null));
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
