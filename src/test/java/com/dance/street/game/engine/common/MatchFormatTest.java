package com.dance.street.game.engine.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 淘汰赛 BO 解析:赛段默认、本场已写入的 format,以及空值回退。
 */
class MatchFormatTest {

    @Test
    void ofRuleConfigReadsTopLevelFormat() {
        assertEquals(MatchFormat.BO3, MatchFormat.of("{\"mode\":\"KNOCKOUT\",\"format\":\"BO3\"}"));
        assertEquals(MatchFormat.BO5, MatchFormat.of(
            "{\"mode\":\"KNOCKOUT\",\"knockout\":{\"format\":\"BO5\"}}"));
        assertEquals(MatchFormat.BO1, MatchFormat.of((String) null));
        assertEquals(MatchFormat.BO1, MatchFormat.of("{}"));
    }

    @Test
    void isValidCodeAcceptsOnlyExplicitBoValues() {
        assertTrue(MatchFormat.isValidCode("BO1"));
        assertTrue(MatchFormat.isValidCode("bo3"));
        assertTrue(MatchFormat.isValidCode("BO5"));
        assertFalse(MatchFormat.isValidCode(null));
        assertFalse(MatchFormat.isValidCode(""));
        assertFalse(MatchFormat.isValidCode("BO7"));
        assertFalse(MatchFormat.isValidCode("BEST"));
    }

    @Test
    void ofStoredPrefersMatchFormatAndFallsBackWhenEmpty() {
        assertEquals(MatchFormat.BO3, MatchFormat.ofStored("BO3", MatchFormat.BO1));
        assertEquals(MatchFormat.BO5, MatchFormat.ofStored("bo5", MatchFormat.BO1));
        assertEquals(MatchFormat.BO3, MatchFormat.ofStored(null, MatchFormat.BO3),
            "尚未开赛、字段为空时应回退赛段默认");
        assertEquals(MatchFormat.BO5, MatchFormat.ofStored("  ", MatchFormat.BO5));
        assertEquals(MatchFormat.BO3, MatchFormat.ofStored("BO7", MatchFormat.BO3),
            "非法编码不能当成 BO1,应回退 fallback");
        assertEquals(MatchFormat.BO1, MatchFormat.ofStored(null, null));
    }
}
