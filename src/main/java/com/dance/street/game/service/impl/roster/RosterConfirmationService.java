package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
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
 * → 逐行物化 → 回写状态位与行↔参赛方的挂钩。</p>
 *
 * @author duane
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterConfirmationService {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TMatchMapper matchMapper;
    private final TStageRosterEntryMapper entryMapper;
    /** 来源组(出口)的存储:读、按 id 差异保存、变更广播 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 名单投影的空闲座位口径 */
    private final RosterProjectionService rosterProjectionService;
    /** 出口规则与名单状态/守卫 */
    private final RosterGroupService rosterGroupService;

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
        Set<String> existingPlayerKeys = memberPlayerKeys(existing);
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
            throw new ServiceException("待落位区还有 {} 人未处理，无法确认名单（请先在中间态把他们拖到座位上）", unplaced);
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
            RosterProjectionService.AssembledRow row = new RosterProjectionService.AssembledRow();
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

    /**
     * 把上一赛段的参赛方复制成本赛段的参赛方(带成员行)。
     *
     * @param seedOverride 中间态指定的座位号;为空则取下一个空位
     * @return 新增人数(1;并发下已被别处物化而命中唯一索引时为 0)
     */
    public int copyIntoStage(TStage target, TCompetitor source, Set<Long> occupiedSeeds,
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
            seed = rosterProjectionService.nextFreeSeed(occupiedSeeds, plan);
        }
        TCompetitor created = new TCompetitor();
        created.setTenantId(target.getTenantId());
        created.setTournamentId(target.getTournamentId());
        created.setStageId(target.getId());
        created.setSourceCompetitorId(source.getId());
        created.setSourceStageId(source.getStageId());
        created.setFromRoster(1L);
        created.setEntryTag(OutcomeStatusEnum.ADVANCE.getCode().equals(source.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE);
        created.setType(source.getType());
        created.setName(source.getName());
        created.setNumber(source.getNumber());
        created.setSeedRank(seed);
        created.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        created.setRemark(source.getRemark());
        try {
            competitorMapper.insert(created);
        } catch (RuntimeException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            // 并发的另一次"确认名单"已经把同一个来源人物化进本赛段:
            // 它的结果就是我们要的结果,不重复建成员行、不占座。
            log.warn("赛段[{}]物化来源参赛方[{}]时命中唯一索引,视为已由并发请求物化,跳过",
                target.getId(), source.getId());
            return 0;
        }
        for (TCompetitorMember sourceMember : competitorMemberMapper.selectList(
            Wrappers.<TCompetitorMember>lambdaQuery()
                .eq(TCompetitorMember::getCompetitorId, source.getId()))) {
            TCompetitorMember member = new TCompetitorMember();
            member.setTenantId(created.getTenantId());
            member.setTournamentId(created.getTournamentId());
            member.setCompetitorId(created.getId());
            member.setPlayerId(sourceMember.getPlayerId());
            member.setRole(sourceMember.getRole());
            member.setRemark(sourceMember.getRemark());
            competitorMemberMapper.insert(member);
        }
        occupiedSeeds.add(seed);
        return 1;
    }

    /**
     * 是否唯一键冲突:MySQL 经 Spring 翻译成 {@link DuplicateKeyException};
     * SQLite 没有对应的错误码表,翻译后不一定是该类型,所以再按 SQLState 23xxx / 报错文案兜一层。
     */
    public static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                String msg = sql.getMessage() == null ? "" : sql.getMessage();
                if ((state != null && state.startsWith("23"))
                    || msg.contains("UNIQUE constraint failed")
                    || msg.contains("Duplicate entry")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 把一张外卡写成目标赛段的参赛方(可选关联选手);号码缺省自动分配 G 开头 */
    public int copyGuestIntoStage(TStage target, RosterProjectionService.AssembledRow row,
                                  Set<Long> occupiedSeeds) {
        String number = row.guestNumber != null && !row.guestNumber.isBlank()
            ? row.guestNumber : nextGuestNumber(target);
        TCompetitor created = new TCompetitor();
        created.setTenantId(target.getTenantId());
        created.setTournamentId(target.getTournamentId());
        created.setStageId(target.getId());
        created.setFromRoster(1L);
        created.setEntryTag(RosterConstants.ENTRY_GUEST);
        created.setType(row.guestType == null ? 0L : row.guestType);
        created.setName(row.guestName);
        created.setNumber(number);
        created.setSeedRank(row.seedRank);
        created.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(created);
        if (row.guestPlayerId != null) {
            TCompetitorMember member = new TCompetitorMember();
            member.setTenantId(target.getTenantId());
            member.setTournamentId(target.getTournamentId());
            member.setCompetitorId(created.getId());
            member.setPlayerId(row.guestPlayerId);
            member.setRole("MEMBER");
            competitorMemberMapper.insert(member);
        }
        occupiedSeeds.add(row.seedRank);
        log.info("赛段[{}]物化外卡[{}](id={}, 号={}, 种子={})",
            target.getId(), row.guestName, created.getId(), number, row.seedRank);
        return 1;
    }

    /** 目标赛段内已有键的选手:已有参赛方关联的选手(用于装配去重) */
    public Set<String> memberPlayerKeys(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Set.of();
        }
        List<Long> competitorIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, competitorIds)
                .select(TCompetitorMember::getPlayerId))
            .stream()
            .map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toSet());
    }

    /** 参赛方 → 关联选手列表(装配时把成员行一起带过去) */
    public Map<Long, List<Long>> memberPlayersOf(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Map.of();
        }
        List<Long> competitorIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, competitorIds)
                .select(TCompetitorMember::getCompetitorId, TCompetitorMember::getPlayerId))
            .stream()
            .collect(Collectors.groupingBy(TCompetitorMember::getCompetitorId,
                Collectors.mapping(TCompetitorMember::getPlayerId, Collectors.toList())));
    }

    /** 外卡号码自动分配:已有号码里取最大数值 +1,前缀 G */
    private String nextGuestNumber(TStage stage) {
        List<String> numbers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getNumber))
            .stream().map(TCompetitor::getNumber).filter(Objects::nonNull).toList();
        long maxNum = 0L;
        for (String number : numbers) {
            if (number.matches("\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(number));
            } else if (number.matches("G\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(number.substring(1)));
            }
        }
        return "G" + (maxNum + 1);
    }
}
