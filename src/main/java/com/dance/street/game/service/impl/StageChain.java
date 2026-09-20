package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
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
 * <p><b>指针列只在本类里写。</b>业务代码不再自己拼 prev/next:接入新赛段用
 * {@link #insertAfter}、移动已有赛段用 {@link #moveAfter},两者都按「期望的链顺序」
 * 重排后一次性修正指针,既不产生「先断链再接线」的中间态,也不读客户端传来的指针。
 * 客户端手里那份 prev/next 只是它某一时刻的副本,一旦过期写回就会把链写歪——
 * 这正是「改个场次配置却动了赛段链」的成因。</p>
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

    /**
     * 把新建赛段接入链:插到 {@code afterStageId} 之后,{@code afterStageId} 为 null 表示插到链头。
     *
     * <p>调用前被插入行必须已落库(本方法按赛事重新推导整条链,再修正指针列)。</p>
     *
     * @return 本次指针列被实际改写的赛段ID(链尾追加时可能没有下游)
     * @throws ServiceException 赛段缺少 id/tournamentId,或 {@code afterStageId} 不在本赛事的链上
     */
    public List<Long> insertAfter(TStage created, Long afterStageId) {
        if (created == null || created.getId() == null || created.getTournamentId() == null) {
            throw new ServiceException("新建赛段缺少 id 或 tournamentId,无法接入赛段链");
        }
        List<TStage> chain = new ArrayList<>(orderedChain(created.getTournamentId()));
        // 新建行此时已在库里,会出现在 orderedChain 结果里;先摘掉再按意图插入
        chain.removeIf(s -> Objects.equals(s.getId(), created.getId()));
        chain.add(insertPosition(chain, afterStageId), created);
        return applyPointers(chain);
    }

    /**
     * 把已有赛段移动到 {@code afterStageId} 之后,{@code afterStageId} 为 null 表示移到链头。
     *
     * <p>与 {@link #insertAfter} 同源:先算出期望顺序,再只写与当前列不同的指针。
     * 因为顺序是从现有链推导出来的线性表,把赛段挪到它自己的下游后面也只会得到
     * 一个新的线性顺序,不存在成环的可能。</p>
     *
     * @return 本次指针列被实际改写的赛段ID
     * @throws ServiceException 赛段不存在,或移动到它自己后面/目标不在本赛事链上
     */
    public List<Long> moveAfter(Long stageId, Long afterStageId) {
        TStage stage = stageId == null ? null : stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段[{}]不存在,无法调整链顺序", stageId);
        }
        if (Objects.equals(stageId, afterStageId)) {
            throw new ServiceException("不能把赛段[{}]移动到它自己后面", stage.getName());
        }
        List<TStage> chain = new ArrayList<>(orderedChain(stage.getTournamentId()));
        boolean removed = chain.removeIf(s -> Objects.equals(s.getId(), stageId));
        if (!removed) {
            // 被丢弃(DISCARD)的赛段不在链上,不参与重排
            throw new ServiceException("赛段[{}]已不在赛段链上(可能已作废),无法调整顺序", stage.getName());
        }
        chain.add(insertPosition(chain, afterStageId), stage);
        List<Long> changed = applyPointers(chain);
        log.info("赛段[{}]移动到[{}]之后完成,指针改写 {} 个赛段", stageId, afterStageId, changed.size());
        return changed;
    }

    /** 插入下标:afterStageId 为 null 取链头,否则取目标之后;目标不在链上直接拒绝 */
    private int insertPosition(List<TStage> chain, Long afterStageId) {
        if (afterStageId == null) {
            return 0;
        }
        for (int i = 0; i < chain.size(); i++) {
            if (Objects.equals(chain.get(i).getId(), afterStageId)) {
                return i + 1;
            }
        }
        throw new ServiceException("指定的前驱赛段[{}]不在本赛事的赛段链上", afterStageId);
    }

    /**
     * 删除赛段后的链收口:按当前 next 链重算顺序并修正指针列。
     *
     * <p>被删节点已不在库里,重算出来的顺序天然是被前后邻居跨过的样子
     * (A→B→C 删 B 得 A→C),因此删除流程不需要自己拼「prev.next 指向被删的 next」——
     * 那种手写拼接在删除多个相邻赛段、或指针本身已歪时会把链拼错。</p>
     *
     * @param tournamentId 赛事ID
     * @return 指针被实际改写的赛段ID
     */
    public List<Long> realignPointers(Long tournamentId) {
        if (tournamentId == null) {
            return List.of();
        }
        return applyPointers(new ArrayList<>(orderedChain(tournamentId)));
    }

    /**
     * 按给定顺序修正 prev/next 列,只写与库中现值不同的节点。
     *
     * <p>这是全项目唯一写指针列的地方:任何一次改链都让「期望顺序」和「库中链」在
     * 同一个事务里重合,不依赖调用方传来的指针,也就没有过期副本写歪链的窗口。</p>
     *
     * @param chain 期望的链顺序(已按 next 链推导,含首尾)
     * @return 指针被实际改写的赛段ID
     */
    private List<Long> applyPointers(List<TStage> chain) {
        List<Long> changed = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            TStage node = chain.get(i);
            Long expectedPrev = i == 0 ? null : chain.get(i - 1).getId();
            Long expectedNext = i == chain.size() - 1 ? null : chain.get(i + 1).getId();
            if (Objects.equals(node.getPrevStageId(), expectedPrev)
                && Objects.equals(node.getNextStageId(), expectedNext)) {
                continue;
            }
            stageMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                .eq(TStage::getId, node.getId())
                .set(TStage::getPrevStageId, expectedPrev)
                .set(TStage::getNextStageId, expectedNext));
            node.setPrevStageId(expectedPrev);
            node.setNextStageId(expectedNext);
            changed.add(node.getId());
        }
        return changed;
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
