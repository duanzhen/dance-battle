package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛段链遍历的唯一入口。
 *
 * <p>赛段链以 <b>next 链为唯一事实源</b>:谁是「上一赛段」由「谁的 next 指向我」推导,
 * 而不是读 t_stage.prev_stage_id。prev 列仍由链维护逻辑照常写入(前端/VO 展示继续可用),
 * 但所有正确性判断都走本类——两个指针万一不同步,一律以 next 为准。</p>
 *
 * <p>反向链之所以不再是事实源,是因为它可由 next 完全推导,却额外引入
 * 「A.next=B 但 B.prev≠A」这类不同步状态;此前系统为此散落了三处兜底补丁
 * (resolvePrevStage 悬空反查、删除后的指针清扫、过期指针回退)。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class StageChain {

    private final TStageMapper stageMapper;

    /**
     * 上一赛段:同赛事内 next 指向该赛段的那一段(排除 DISCARD)。
     * 无人以 next 指向它时返回 null,表示它是链头。
     */
    public TStage prevOf(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return null;
        }
        return stageMapper.selectOne(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, stage.getTournamentId())
            .eq(TStage::getNextStageId, stage.getId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .last("LIMIT 1"));
    }

    /** 入口赛段:链上没有任何赛段的 next 指向它(名单默认来源组按此决定用签到还是上游晋级) */
    public boolean isEntry(TStage stage) {
        return prevOf(stage) == null;
    }

    /** 链头:按 id 升序取第一个「无人以 next 指向」的存活赛段;全部被指向时退回 id 最小者 */
    public TStage headOf(Long tournamentId) {
        return headOf(aliveStages(tournamentId));
    }

    /** 按 next 链顺序返回全部存活赛段;断链/链外的赛段按 id 顺序补在末尾 */
    public List<TStage> orderedChain(Long tournamentId) {
        List<TStage> all = aliveStages(tournamentId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, TStage> byId = new HashMap<>();
        for (TStage s : all) {
            byId.put(s.getId(), s);
        }
        List<TStage> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        TStage cur = headOf(all);
        while (cur != null && visited.add(cur.getId())) {
            chain.add(cur);
            cur = byId.get(cur.getNextStageId());
        }
        // 断链/自成环的赛段没有走到,按 id 顺序补齐,保证不漏赛段
        for (TStage s : all) {
            if (!visited.contains(s.getId())) {
                chain.add(s);
            }
        }
        return chain;
    }

    /** 本赛事存活赛段(排除 DISCARD),按 id 升序 */
    private List<TStage> aliveStages(Long tournamentId) {
        if (tournamentId == null) {
            return List.of();
        }
        return stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, tournamentId)
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .orderByAsc(TStage::getId));
    }

    /** 链头判定与 prevOf 同口径:被任一存活赛段的 next 指向即不是链头 */
    private TStage headOf(List<TStage> all) {
        if (all.isEmpty()) {
            return null;
        }
        Set<Long> referenced = new HashSet<>();
        for (TStage s : all) {
            if (s.getNextStageId() != null) {
                referenced.add(s.getNextStageId());
            }
        }
        return all.stream().filter(s -> !referenced.contains(s.getId())).findFirst().orElse(all.get(0));
    }
}
