package com.dance.street.game.roster;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.service.impl.roster.RosterEdgeRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 边类型口径(AUTO/MANUAL/STREAM)的纯函数测试。 */
class RosterEdgeRulesTest {

    private static TStageRosterGroupBo edge(String fill, Long source) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setFillMode(fill);
        g.setSourceStageId(source);
        return g;
    }

    @Test
    void edgeKinds() {
        assertTrue(RosterEdgeRules.isAutoEdge(edge("AUTO", 1L)));
        assertFalse(RosterEdgeRules.isAutoEdge(edge("AUTO", null)));
        assertFalse(RosterEdgeRules.isAutoEdge(edge("MANUAL", 1L)));
        assertFalse(RosterEdgeRules.isAutoEdge(edge("STREAM", null)));
        assertTrue(RosterEdgeRules.isManualEdge(edge("MANUAL", null)));
    }

    @Test
    void readinessCountsAutoAndManualWithSourceButNotStream() {
        assertTrue(RosterEdgeRules.countsForReadiness(edge("AUTO", 1L)));
        assertTrue(RosterEdgeRules.countsForReadiness(edge("MANUAL", 1L)));
        assertFalse(RosterEdgeRules.countsForReadiness(edge("MANUAL", null)));
        assertFalse(RosterEdgeRules.countsForReadiness(edge("STREAM", 1L)));
    }

    @Test
    void multiEntryIsDecidedByEdgeEntitiesAndIgnoresManualEdges() {
        TStageRosterGroupBo a = edge("AUTO", 1L);
        TStageRosterGroupBo b = edge("AUTO", 2L);
        TStageRosterGroupBo m = edge("MANUAL", 3L);
        assertFalse(RosterEdgeRules.isMultiEntry(List.of(a)));
        assertFalse(RosterEdgeRules.isMultiEntry(List.of(a, m)));
        assertTrue(RosterEdgeRules.isMultiEntry(List.of(a, b)));
        assertTrue(RosterEdgeRules.isMultiEntry(List.of(a, b, m)));
        assertFalse(RosterEdgeRules.isMultiEntry(List.of(edge("STREAM", null))));
        assertEquals(List.of(a), RosterEdgeRules.entryEdges(List.of(a, m)));
        assertTrue(RosterEdgeRules.hasManualEdge(List.of(a, m)));
        assertFalse(RosterEdgeRules.hasManualEdge(List.of(a, b)));
    }
}
