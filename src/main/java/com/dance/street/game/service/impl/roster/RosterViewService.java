package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 名单的对外视图:赛段参赛选手(大屏)与中间态名单预览。
 *
 * <p>从 {@code TStageRosterServiceImpl} 搬出来的第三块。只读、零副作用:已确认就读目标层
 * 快照,未确认就读中间层——两条路径都不再按规则重算,避免出现"预览一套、落库一套"。</p>
 *
 * @author duane
 */
@Service
@RequiredArgsConstructor
public class RosterViewService {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TPlayerMapper playerMapper;
    /** 来源组(出口)的读口径 */
    private final RosterGroupStore rosterGroupStore;
    /** 中间层名单的行级读写 */
    private final RosterEntryStore rosterEntryStore;
    /** 名单状态位与锁口径 */
    private final RosterGroupService rosterGroupService;

    /**
     * 赛段参赛选手:名单已物化读目标层(真实参赛方),未物化读中间层名单。
     * 两个分支都按"这个赛段有哪些人"取,不掺晋级/淘汰的业务判断;未落位的人照样返回,
     * 只是 {@code seedRank=null, holding=true}。
     */
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
        if (rosterGroupService.isApplied(stage)) {
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
        List<TStageRosterEntry> entries = rosterEntryStore.entriesOf(stageId);
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

    public RosterPreviewVo previewAssembled(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterPreviewVo vo = new RosterPreviewVo();
        vo.setStageId(stageId);
        vo.setTargetStageId(stageId);
        List<TStageRosterGroupBo> groups = rosterGroupStore.groupsOf(stage);
        vo.setReady(rosterEntryStore.readyByGroups(groups));
        vo.setApplied(rosterGroupService.isApplied(stage));
        vo.setSkipped(rosterGroupService.isSkipped(stage));
        int plan = stage.getTeamCountStart() == null || stage.getTeamCountStart() <= 0
            ? 0 : stage.getTeamCountStart().intValue();
        vo.setCapacity(plan);
        // 已确认:直接展示落库的名单快照(种子位/来源标签都是真实值);
        // 不能再按规则重算——快照行已占满种子位,重算会把所有人的次序算成计划外
        if (rosterGroupService.isApplied(stage)) {
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
        List<TStageRosterEntry> entries = rosterEntryStore.entriesOf(stageId);
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
}
