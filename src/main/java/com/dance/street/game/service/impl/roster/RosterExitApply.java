package com.dance.street.game.service.impl.roster;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.impl.roster.RosterAssembler.AssembledRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 赛段出口边界:对一条出边做座位投影(含非从第1名切片时的压紧)。
 *
 * <p>压紧语义只属于出口边,不属于中间态投影的「按来源 mode 偷偷改座」。
 * 本类是压紧算法的唯一实现;{@link RosterAssembler} 的中间态投影只消费这里给出的目标边座位,
 * 不再自行按 AUDITION/RANK 分支决定是否 compact。</p>
 *
 * <p>约定:不改写来源 {@code final_rank},只写目标边 {@link AssembledRow#seedRank};
 * 淘汰/签表边(非海选/排名来源)保持空洞座号,不做压紧。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class RosterExitApply {

    private final TStageMapper stageMapper;

    /**
     * 该出边是否应在切片后压紧目标座位。
     *
     * <p>口径与历史一致:来源为海选/排名赛时压紧(名次是累加序号,不是签表位置);
     * 淘汰等签表来源禁止压紧。多入边汇合由调用方整单进待落位,不会走到压紧。</p>
     */
    public boolean shouldCompact(TStageRosterGroupBo edge) {
        if (edge == null || edge.getSourceStageId() == null) {
            return false;
        }
        TStage source = stageMapper.selectById(edge.getSourceStageId());
        if (source == null) {
            return false;
        }
        String mode = source.getStageMode();
        return StageModeEnum.AUDITION.getCode().equals(mode)
            || StageModeEnum.RANK.getCode().equals(mode);
    }

    /**
     * 单入边出口落座:按是否压紧写入目标边座位。
     *
     * @param rows     该边(或合并后)已切片取到的人;来源 final_rank 只读
     * @param plan     目标赛段计划人数(容量)
     * @param occupied 已占用座位
     * @param compact  true = 整体下移 minRank-1 压掉前部空档;中间空洞保留
     */
    public void assignEdgeSeats(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds, boolean compact) {
        Set<Long> occupied = occupiedSeeds == null ? new HashSet<>() : occupiedSeeds;
        if (compact) {
            compactAssign(rows, plan, occupied);
            return;
        }
        copyAssign(rows, plan, occupied);
    }

    /** 单入边便捷入口:按 {@link #shouldCompact} 决定是否压紧。 */
    public void assignEdgeSeats(List<AssembledRow> rows, int plan, TStageRosterGroupBo edge) {
        assignEdgeSeats(rows, plan, new HashSet<>(), shouldCompact(edge));
    }

    /**
     * 海选/排名出口压紧:名次整体下移 min-1。
     * 例:出口取 9~24 → 目标座 1..16;中间空洞 3/4/7 → 1/2/5。
     */
    private void compactAssign(List<AssembledRow> rows, int plan, Set<Long> occupied) {
        int minRank = rows.stream()
            .map(r -> r.source == null ? null : r.source.getFinalRank())
            .filter(Objects::nonNull)
            .mapToInt(Long::intValue)
            .min().orElse(1);
        long shift = Math.max(0L, (long) minRank - 1L);
        for (AssembledRow r : rows) {
            Long rank = r.source == null ? null : r.source.getFinalRank();
            Long seat = rank == null ? null : rank - shift;
            if (seat != null && seat >= 1L && (plan <= 0 || seat <= plan) && occupied.add(seat)) {
                r.seedRank = seat;
                continue;
            }
            if (rank != null) {
                r.seedRank = null;
                continue;
            }
            Long free = nextFreeSeedOrNull(occupied, plan);
            r.seedRank = free;
            if (free != null) {
                occupied.add(free);
            }
        }
    }

    /** 签表/默认:原样复制来源座号(空洞即位置)。 */
    private void copyAssign(List<AssembledRow> rows, int plan, Set<Long> occupied) {
        for (AssembledRow r : rows) {
            Long seat = r.source == null ? null : r.source.getFinalRank();
            if (seat != null && seat >= 1L && (plan <= 0 || seat <= plan) && occupied.add(seat)) {
                r.seedRank = seat;
                continue;
            }
            if (seat != null && seat >= 1L && plan > 0 && seat > plan) {
                r.seedRank = null;
                continue;
            }
            Long free = nextFreeSeedOrNull(occupied, plan);
            r.seedRank = free;
            if (free != null) {
                occupied.add(free);
            }
        }
    }

    private Long nextFreeSeedOrNull(Set<Long> occupiedSeeds, int plan) {
        if (plan > 0) {
            for (long s = 1; s <= plan; s++) {
                if (!occupiedSeeds.contains(s)) {
                    return s;
                }
            }
            return null;
        }
        return occupiedSeeds.stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
    }
}
