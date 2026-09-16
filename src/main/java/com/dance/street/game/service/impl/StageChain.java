package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
@Slf4j
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

    /**
     * 链上推导的「赛段ID → 前驱ID」映射(无前驱的赛段不出现在 Map 里)。
     *
     * <p>口径与 {@link #prevOf} 一致:被任一非 DISCARD 赛段的 next 指向即视为有前驱;
     * 多个赛段指向同一目标时取 id 最小者,保证结果稳定。</p>
     *
     * <p>供列表接口一次性把 prev 展示字段按链覆盖:既避免逐个 {@code prevOf} 的 N+1,
     * 也让 prev 列彻底退出读取路径——管理端流程图用 prevStageId 找头节点并排序,
     * 列一旦过期整条链都会排错。</p>
     */
    public Map<Long, Long> prevIdsFromChain(Long tournamentId) {
        List<TStage> all = aliveStages(tournamentId);
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> prev = new HashMap<>();
        for (TStage s : all) {
            if (s.getNextStageId() != null) {
                // all 已按 id 升序,putIfAbsent 即"id 最小者优先"
                prev.putIfAbsent(s.getNextStageId(), s.getId());
            }
        }
        return prev;
    }

    /**
     * 用 next 链校正 prev 展示列(两个方向都修)。
     *
     * <p>prev 是纯展示字段,事实源只有 next。此前只在开赛守卫里拦了
     * 「列上有值、链上无人指向」这一个方向,反向(链上有前驱、列为空)没人管,
     * 前端「上一赛段」就会显示成「未知」。既然链是事实源,两个方向都以链为准
     * 直接修正,并记一条 warn 便于追查是哪个入口把列写歪的。</p>
     *
     * @return true 表示列确实被修正过
     */
    public boolean reconcilePrevColumn(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return false;
        }
        TStage chainPrev = prevOf(stage);
        Long expected = chainPrev == null ? null : chainPrev.getId();
        if (Objects.equals(expected, stage.getPrevStageId())) {
            return false;
        }
        log.warn("赛段[{}]的 prev 展示列与赛段链不一致(列={}, 链={}),已按链修正",
            stage.getId(), stage.getPrevStageId(), expected);
        stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
            .eq(TStage::getId, stage.getId())
            .set(TStage::getPrevStageId, expected));
        stage.setPrevStageId(expected);
        return true;
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
