package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 整单装配(确认名单):把中间层的行一次性物化成目标赛段的正式参赛方。
 *
 * <p>从 {@code TStageRosterServiceImpl} 搬出来的编排层。中间层是"唯一事实"——
 * 规则生成与人工调整都已经落在行上,这里只做:兜底默认衔接 → 校验(来源结算/对阵/落位/计划/重复)
 * → 逐行调用 {@link RosterMaterializer} 物化 → 回写状态位与行↔参赛方的挂钩。</p>
 *
 * @author duane
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterApplyService {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TStageRosterEntryMapper entryMapper;
    /** 来源组(出口)的存储:读、按 id 差异保存、变更广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 出口规则与名单状态/守卫 */
    private final RosterGroupService rosterGroupService;
    /** 逐行物化:中间层的一行 → 目标赛段的正式参赛方 */
    private final RosterMaterializer rosterMaterializer;

    @Transactional(rollbackFor = Exception.class)
    public int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections) {
        TStage target = stageMapper.selectById(targetStageId);
        if (target == null) {
            throw new ServiceException("赛段不存在");
        }
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT)的赛段可装配名单,当前: {}", target.getStatus());
        }
        if (rosterGroupService.isLocked(target)) {
            return 0;
        }
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(target);
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
        if (!rosterEntryStore.readyByGroups(groups)) {
            throw new ServiceException("名单来源尚未全部结算,请等待后再确认名单");
        }
        rosterGroupService.assertNoPendingInSources(groups);

        List<TCompetitor> existing = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, targetStageId)
            .orderByAsc(TCompetitor::getId));
        if (Long.valueOf(1L).equals(target.getIsInitialized()) && !existing.isEmpty()) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法装配");
        }
        int plan = target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            ? target.getTeamCountStart().intValue() : 0;
        Set<String> existingPlayerKeys = rosterMaterializer.memberPlayerKeys(existing);
        Map<Long, TCompetitor> existingBySource = new HashMap<>();
        for (TCompetitor c : existing) {
            if (c.getSourceCompetitorId() != null) {
                existingBySource.put(c.getSourceCompetitorId(), c);
            }
        }

        // 唯一事实:中间层的行(规则生成 + 人工调整都已落在这里)
        List<TStageRosterEntry> players = rosterEntryStore.entriesOf(targetStageId).stream()
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
            RosterAssembler.AssembledRow row = new RosterAssembler.AssembledRow();
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
                playersOfRow = rosterMaterializer.memberPlayersOf(List.of(src)).getOrDefault(src.getId(), List.of());
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
                created += rosterMaterializer.copyGuestIntoStage(target, row, occupiedSeeds);
            } else {
                created += rosterMaterializer.copyIntoStage(target, row.source, occupiedSeeds, playersOfRow, row.seedRank);
            }
        }
        if (created > 0 || !players.isEmpty()) {
            TStage upd = new TStage();
            upd.setId(targetStageId);
            upd.setRosterApplied(1L);
            stageMapper.updateById(upd);
        }
        // 中间层的行与目标层参赛方挂钩:确认后可用于回溯"当时确认了谁"
        // 一次取回本赛段全部带来源的参赛方并建映射,替代逐行 selectOne(32/64 人)
        Map<Long, Long> createdIdBySource = new HashMap<>();
        if (!sourceIds.isEmpty()) {
            for (TCompetitor c : competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, targetStageId)
                .in(TCompetitor::getSourceCompetitorId, sourceIds))) {
                if (c.getSourceCompetitorId() != null) {
                    createdIdBySource.putIfAbsent(c.getSourceCompetitorId(), c.getId());
                }
            }
        }
        for (TStageRosterEntry e : players) {
            if (e.getSourceCompetitorId() == null) {
                continue;
            }
            Long createdId = createdIdBySource.get(e.getSourceCompetitorId());
            if (createdId != null && !Objects.equals(createdId, e.getCompetitorId())) {
                entryMapper.update(null, Wrappers.<TStageRosterEntry>lambdaUpdate()
                    .eq(TStageRosterEntry::getId, e.getId())
                    .set(TStageRosterEntry::getCompetitorId, createdId));
            }
        }
        log.info("赛段[{}]整单装配完成:新增 {} 人(中间层 {} 人)", targetStageId, created, players.size());
        rosterGroupStore.notifyTarget(targetStageId);
        return created;
    }
}
