package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 名单物化:把中间层的一行写成目标赛段的正式参赛方(含成员行)。
 *
 * <p>从 {@code TStageRosterServiceImpl} 拆出来的叶子物化动作,只依赖 mapper 与装配器
 * (取下一个空座位),不依赖出口配置与中间层存储。上层的 {@code applyRoster} 仍是编排者:
 * 它决定"带谁进来、够不够位",这里只负责"把一个人落成参赛方"。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterMaterializer {

    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final RosterAssembler rosterAssembler;

    /**
     * 把上一赛段的参赛方复制成本赛段的参赛方(带成员行)。
     *
     * @param seedOverride 中间态指定的座位号;为空则取下一个空位
     * @return 新增人数(恒为 1,便于调用方累加)
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
            seed = rosterAssembler.nextFreeSeed(occupiedSeeds, plan);
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

    /** 把一张外卡写成目标赛段的参赛方(可选关联选手);号码缺省自动分配 G 开头 */
    public int copyGuestIntoStage(TStage target, RosterAssembler.AssembledRow row, Set<Long> occupiedSeeds) {
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

    /** 目标赛段内已有键的选手:已有参赛方关联的选手(用于装配去重) */
    public Set<String> memberPlayerKeys(List<TCompetitor> competitors) {
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

    /** 参赛方 → 关联选手列表(装配时把成员行一起带过去) */
    public Map<Long, List<Long>> memberPlayersOf(List<TCompetitor> competitors) {
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

    /** 外卡号码自动分配:已有号码里取最大数值 +1,前缀 G */
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
}
