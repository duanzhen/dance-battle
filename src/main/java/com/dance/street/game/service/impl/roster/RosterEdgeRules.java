package com.dance.street.game.service.impl.roster;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;

import java.util.List;

/**
 * 入口边(边表实体)口径的<b>唯一定义处</b>:一条边「算什么」只在这里回答,其他类只调用、不再各写一套。
 *
 * <p>边类型三种:</p>
 * <ul>
 *   <li><b>AUTO</b>:按规则从来源赛段自动取人({@link #isAutoEdge});</li>
 *   <li><b>MANUAL</b>:自己不取人,只表示「这个赛段允许导播手工加人」。手动的人是导播添加的记录
 *       (origin=MANUAL 的中间层行),不是由这条边取出来的;</li>
 *   <li><b>STREAM</b>:只出现在首个赛段,且该赛段只有这一条边(现场签到/外部流)。</li>
 * </ul>
 *
 * <p>入口条数由<b>边表实体</b>决定(赛段配了几条边),与人从哪来、边实际取出几个人无关:
 * {@link #entryEdges} 排除 MANUAL 边;入口多于一条(多条 AUTO)→ 全部进待落位区由导播拖座;
 * 一条 AUTO + MANUAL 边 → 自动边照常排座,手动的人优先占座({@link #hasManualEdge})。</p>
 *
 * <p>纯静态、无 Spring 依赖。</p>
 *
 * @author duane
 */
public final class RosterEdgeRules {

    private RosterEdgeRules() {
    }

    /** 是否「按规则自动取人」的边:有来源赛段,且既不是 STREAM 也不是 MANUAL */
    public static boolean isAutoEdge(TStageRosterGroupBo g) {
        return g != null
            && g.getSourceStageId() != null
            && !RosterConstants.FILL_STREAM.equals(g.getFillMode())
            && !RosterConstants.FILL_MANUAL.equals(g.getFillMode());
    }

    public static boolean isManualEdge(TStageRosterGroupBo g) {
        return g != null && RosterConstants.FILL_MANUAL.equals(g.getFillMode());
    }

    /** 名单就绪要等这条边的来源结算吗:有来源赛段且不是 STREAM(AUTO 与带来源的 MANUAL 都要等)。 */
    public static boolean countsForReadiness(TStageRosterGroupBo g) {
        return g != null
            && g.getSourceStageId() != null
            && !RosterConstants.FILL_STREAM.equals(g.getFillMode());
    }

    /** 决定「入口数/自动落座」的边:边表里除 MANUAL 以外的边(AUTO、STREAM)。 */
    public static List<TStageRosterGroupBo> entryEdges(List<TStageRosterGroupBo> groups) {
        return groups == null ? List.of() : groups.stream().filter(g -> !isManualEdge(g)).toList();
    }

    /** 多入口:入口边(不含 MANUAL)多于一条 → 系统无依据排座,全部进待落位区。 */
    public static boolean isMultiEntry(List<TStageRosterGroupBo> groups) {
        return entryEdges(groups).size() > 1;
    }

    /** 边表里是否有 MANUAL 边(有 → 导播手动的人在对账时优先于自动边占座)。 */
    public static boolean hasManualEdge(List<TStageRosterGroupBo> groups) {
        return groups != null && groups.stream().anyMatch(RosterEdgeRules::isManualEdge);
    }
}
