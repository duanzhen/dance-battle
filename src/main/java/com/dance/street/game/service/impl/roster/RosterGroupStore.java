package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterGroup;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterGroupMapper;
import com.dance.street.game.service.TournamentEventNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
 * 名单来源组(出口)的存储:读、按 id 差异保存、变更广播。
 *
 * <p>从 {@code TStageRosterServiceImpl} 拆出来的持久化内核。读口径唯一({@code sort_order} 升序),
 * 保存按 id diff 而不是"删光重插"——否则每次保存都换一遍行 ID,前端手里的 ID 立刻失效。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class RosterGroupStore {

    private final TStageRosterGroupMapper groupMapper;
    private final TStageMapper stageMapper;
    private final TournamentEventNotifier tournamentEventNotifier;

    /**
     * 读某赛段的来源组:按取人顺序({@code sort_order})升序。
     *
     * <p>对上层调用点保持了原来的签名,批量场景请用 {@link #groupsOfTargets} 避免 N+1。</p>
     */
    public List<TStageRosterGroupBo> groupsOf(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return List.of();
        }
        return selectGroups(stage.getId());
    }

    public List<TStageRosterGroupBo> selectGroups(Long targetStageId) {
        // 返回可变的 ArrayList:调用方习惯在返回列表上原地增删改(removeGroup/addGroups/...)
        return groupMapper.selectList(Wrappers.<TStageRosterGroup>lambdaQuery()
                .eq(TStageRosterGroup::getTargetStageId, targetStageId)
                .orderByAsc(TStageRosterGroup::getSortOrder)
                .orderByAsc(TStageRosterGroup::getId))
            .stream().map(RosterGroupStore::toGroupBo)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<TStageRosterGroupBo> groupsOfStage(Long targetStageId) {
        return targetStageId == null ? List.of() : selectGroups(targetStageId);
    }

    /** 批量读多个目标赛段的来源组:一次 IN 查询 + 内存分组(替代逐段查询) */
    public Map<Long, List<TStageRosterGroupBo>> groupsOfTargets(Collection<Long> targetStageIds) {
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
    public Set<Long> targetsReferencing(Collection<Long> sourceStageIds) {
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

    public void saveGroups(TStage stage, List<TStageRosterGroupBo> groups) {
        saveGroups(stage, groups, stage.getRosterApplied(), stage.getRosterSkipped());
    }

    /**
     * 保存来源组:按 id diff(新增 insert / 已有 update / 缺失 delete),不整包重写。
     *
     * <p>按 id 而不是"删光重插":否则每次保存都会换一遍行 ID,前端手里的 ID 立刻失效。</p>
     */
    public void saveGroups(TStage stage, List<TStageRosterGroupBo> groups, Long applied, Long skipped) {
        replaceGroups(stage, groups);
        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setRosterApplied(applied);
        upd.setRosterSkipped(skipped);
        stageMapper.updateById(upd);
    }

    /** 名单(来源组)变更后广播,让大屏/导播台刷新 */
    public void notifyTarget(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage != null) {
            tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        }
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
}
