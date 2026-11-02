package com.dance.street.game.service.impl.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.service.impl.roster.RosterAssembler.AssembledRow;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 出口名额切片:「按出边 quota 截取候选并包成装配行」的<b>唯一实现</b>。
 *
 * <p>纯静态、无 Spring 依赖(Assembler 与 Commands 都用它,不产生 Bean 间循环依赖)。
 * 候选必须已按出边规则取好并排好序({@link RosterAssembler#groupRows});这里只负责截 quota、
 * 记住来源边、打入场标签。座位计算(压紧/复制)仍只在 {@link RosterExitApply}。</p>
 *
 * @author duane
 */
@Slf4j
final class RosterQuotaSlice {

    private RosterQuotaSlice() {
    }

    /** 是否「按规则自动取人」的出边:口径唯一定义在 {@link RosterEdgeRules#isAutoEdge} */
    static boolean isAutoEdge(TStageRosterGroupBo g) {
        return RosterEdgeRules.isAutoEdge(g);
    }

    /** 名额上限;未配置或 ≤0 视为不限 */
    static int quotaLimit(TStageRosterGroupBo g) {
        return g.getQuota() != null && g.getQuota() > 0 ? g.getQuota() : Integer.MAX_VALUE;
    }

    /**
     * 按名额切片。
     *
     * @param g          出边规则(取 quota 与 id)
     * @param candidates 已过滤、已排序的候选(通常来自 {@code groupRows(g)})
     * @return 带来源边 id 与入场标签的行(新列表,元素互不共享)
     */
    static List<AssembledRow> slice(TStageRosterGroupBo g, List<TCompetitor> candidates) {
        List<AssembledRow> rows = new ArrayList<>();
        int quota = quotaLimit(g);
        int n = 0;
        for (TCompetitor c : candidates) {
            if (n++ >= quota) {
                log.warn("来源组(源赛段 {})配额 {} 已满,剩余候选截断", g.getSourceStageId(), g.getQuota());
                break;
            }
            AssembledRow row = new AssembledRow();
            row.source = c;
            row.sourceGroupId = g.getId();
            row.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
                ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
            rows.add(row);
        }
        return rows;
    }
}
