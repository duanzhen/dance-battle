package com.dance.street.game.stage;

import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五种赛制的取值差异锁在枚举上,避免画像表删掉之后开关悄悄对调。
 */
class StageModeProfileCoverageTest {

    @Test
    void flagsStayWithTheFiveModes() {
        StageModeEnum audition = StageModeEnum.AUDITION;
        assertTrue(audition.generatesBracket());
        assertTrue(audition.perCompetitor());
        assertTrue(audition.audition());
        assertFalse(audition.singleActiveMatch());
        assertFalse(audition.autoStartFirstMatch());
        assertFalse(audition.rejectsBracketGeneration());
        assertEquals(MatchModeEnum.VOTING.getCode(), audition.resolveMatchMode("STANDARD", "STANDARD"));

        StageModeEnum rank = StageModeEnum.RANK;
        assertTrue(rank.generatesBracket());
        assertTrue(rank.perCompetitor());
        assertTrue(rank.rank());
        assertFalse(rank.singleActiveMatch());
        assertEquals(MatchModeEnum.RANKING.getCode(), rank.resolveMatchMode(null, "STANDARD"));

        StageModeEnum knockout = StageModeEnum.KNOCKOUT;
        assertTrue(knockout.generatesBracket());
        assertTrue(knockout.knockout());
        assertTrue(knockout.singleActiveMatch());
        assertFalse(knockout.perCompetitor());
        assertFalse(knockout.autoStartFirstMatch());
        assertEquals("VOTING", knockout.resolveMatchMode("VOTING", "STANDARD"));
        assertEquals("STANDARD", knockout.resolveMatchMode("  ", "STANDARD"));

        StageModeEnum arena = StageModeEnum.ARENA;
        assertFalse(arena.generatesBracket());
        assertTrue(arena.rejectsBracketGeneration());
        assertTrue(arena.autoStartFirstMatch());
        assertTrue(arena.arena());
        assertFalse(arena.singleActiveMatch());

        StageModeEnum free = StageModeEnum.FREE_MATCH;
        assertFalse(free.generatesBracket());
        assertFalse(free.rejectsBracketGeneration());
        assertFalse(free.perCompetitor());
        assertFalse(free.autoStartFirstMatch());
        assertFalse(free.singleActiveMatch());
    }

    @Test
    void unknownModeIsRejected() {
        assertThrows(ServiceException.class, () -> StageModeEnum.require("NOT_A_MODE"));
        assertThrows(ServiceException.class, () -> StageModeEnum.require(null));
        assertThrows(ServiceException.class, () -> StageModeEnum.fromCode("NOT_A_MODE"));
    }
}
