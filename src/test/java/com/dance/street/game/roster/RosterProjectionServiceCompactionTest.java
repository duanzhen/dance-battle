package com.dance.street.game.roster;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.service.impl.roster.RosterProjectionService;
import com.dance.street.game.service.impl.roster.RosterProjectionService.AssembledRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 出口压紧表征:算法与名单装配投影同属 {@link RosterProjectionService}。
 * 9..24 → 目标座 1..16;中间空洞保留;不改写来源 final_rank。
 * 端到端(含 shouldCompact 与重建)见 {@link MergePreAdvanceSeatTest}。
 */
@SpringBootTest
class RosterProjectionServiceCompactionTest {

    @Autowired
    private RosterProjectionService projectionService;

    @Test
    void compactShiftsNineToTwentyFourOntoOneThroughSixteen() {
        List<AssembledRow> rows = ranks(9, 24);
        projectionService.assignEdgeSeats(rows, 16, new HashSet<>(), true);
        List<Long> seats = rows.stream().map(r -> r.seedRank).sorted().toList();
        assertEquals(16, seats.size());
        assertEquals(1L, seats.getFirst());
        assertEquals(16L, seats.getLast());
        assertEquals(9L, rows.getFirst().source.getFinalRank(), "不得改写来源 final_rank");
        assertEquals(24L, rows.getLast().source.getFinalRank());
    }

    @Test
    void compactKeepsMiddleHoles() {
        List<AssembledRow> rows = List.of(rankRow(3L), rankRow(4L), rankRow(7L));
        projectionService.assignEdgeSeats(rows, 16, new HashSet<>(), true);
        assertEquals(List.of(1L, 2L, 5L), rows.stream().map(r -> r.seedRank).toList());
        assertEquals(List.of(3L, 4L, 7L), rows.stream().map(r -> r.source.getFinalRank()).toList());
    }

    @Test
    void copyAssignPreservesHollowSlots() {
        List<AssembledRow> rows = List.of(rankRow(1L), rankRow(4L));
        projectionService.assignEdgeSeats(rows, 4, new HashSet<>(), false);
        assertEquals(1L, rows.get(0).seedRank);
        assertEquals(4L, rows.get(1).seedRank);
    }

    @Test
    void compactOverflowGoesPending() {
        List<AssembledRow> rows = ranks(9, 24);
        projectionService.assignEdgeSeats(rows, 8, new HashSet<>(), true);
        long seated = rows.stream().filter(r -> r.seedRank != null).count();
        long pending = rows.stream().filter(r -> r.seedRank == null).count();
        assertEquals(8, seated);
        assertEquals(8, pending);
        assertNull(rows.stream().filter(r -> r.source.getFinalRank() == 17L).findFirst().orElseThrow().seedRank);
    }

    private static List<AssembledRow> ranks(int from, int to) {
        List<AssembledRow> rows = new ArrayList<>();
        for (int r = from; r <= to; r++) {
            rows.add(rankRow((long) r));
        }
        return rows;
    }

    private static AssembledRow rankRow(long rank) {
        TCompetitor c = new TCompetitor();
        c.setId(rank);
        c.setFinalRank(rank);
        AssembledRow row = new AssembledRow();
        row.source = c;
        return row;
    }
}
