package com.dance.street.game.service.impl.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.service.impl.roster.RosterAssembler.AssembledRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 出口「切片 + 落座/压紧」的唯一入口(生产路径)。
 *
 * <p>{@link RosterAssembler#project} 的单入边分支调用 {@link #exitApply},
 * {@link RosterAssembler#assembleRows} 的逐边取人调用 {@link #sliceEdge};两处都只传「取人」的惰性回调,
 * 本类不依赖 Assembler(无循环依赖),只依赖 {@link RosterExitApply} 与静态 {@link RosterQuotaSlice}。
 * 取人回调只在该边是自动边时才会被调用,与改造前「非自动边不读库」一致。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class RosterCommands {

    private final RosterExitApply rosterExitApply;

    /**
     * 对一条出边执行出口投影:取人 → quota 切片 → 目标边落座(必要时压紧)。
     *
     * <p>不改写来源 competitor.final_rank;只返回带目标 {@link AssembledRow#seedRank} 的行。
     * 非自动出边(外部流/手工填充/无来源)返回空列表且不调用 {@code candidates}。
     * 多入边汇合场景不要对整单调用本方法做压紧——调用方应整单进待落位。</p>
     *
     * @param edge       出边规则
     * @param candidates 按该边规则取出的候选(惰性,已排序、已过滤)
     * @param plan       目标赛段计划容量(teamCountStart);≤0 表示不截容量
     */
    public List<AssembledRow> exitApply(TStageRosterGroupBo edge, Supplier<List<TCompetitor>> candidates, int plan) {
        if (!RosterQuotaSlice.isAutoEdge(edge)) {
            return new ArrayList<>();
        }
        List<AssembledRow> rows = RosterQuotaSlice.slice(edge, candidates.get());
        rosterExitApply.assignEdgeSeats(rows, plan, edge);
        return rows;
    }

    /**
     * 只切片、不落座:多入边汇合时整单进待落位,座位由人工/后续落位决定。
     * 非自动出边返回空列表且不调用 {@code candidates}。
     */
    public List<AssembledRow> sliceEdge(TStageRosterGroupBo edge, Supplier<List<TCompetitor>> candidates) {
        if (!RosterQuotaSlice.isAutoEdge(edge)) {
            return new ArrayList<>();
        }
        return RosterQuotaSlice.slice(edge, candidates.get());
    }

    /** 该边是否出口压紧(测试/表征用)。 */
    public boolean shouldCompact(TStageRosterGroupBo edge) {
        return rosterExitApply.shouldCompact(edge);
    }
}
