package com.dance.street.game.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 选手导入的「标签」列规范化。
 *
 * <p>t_player.tags 在 MySQL 里是 json 列,导入时若把 Excel 原文直接写进去,
 * 只要不是合法 JSON 就会报 Invalid JSON text —— 这里固定住转换规则。</p>
 */
class TPlayerServiceImplTest {

    @Test
    void blankBecomesNull() {
        assertNull(TPlayerServiceImpl.normalizeTags(null));
        assertNull(TPlayerServiceImpl.normalizeTags(""));
        assertNull(TPlayerServiceImpl.normalizeTags("   "));
        assertNull(TPlayerServiceImpl.normalizeTags(" , ; "));
    }

    @Test
    void singleTag() {
        assertEquals("[\"种子\"]", TPlayerServiceImpl.normalizeTags("种子"));
        assertEquals("[\"种子\"]", TPlayerServiceImpl.normalizeTags("  种子  "));
    }

    @Test
    void splitsOnHalfAndFullWidthSeparators() {
        assertEquals("[\"种子\",\"GUEST\"]", TPlayerServiceImpl.normalizeTags("种子,GUEST"));
        assertEquals("[\"种子\",\"GUEST\"]", TPlayerServiceImpl.normalizeTags("种子，GUEST"));
        assertEquals("[\"种子\",\"GUEST\"]", TPlayerServiceImpl.normalizeTags("种子;GUEST"));
        assertEquals("[\"种子\",\"GUEST\",\"明星\"]", TPlayerServiceImpl.normalizeTags("种子， GUEST ； 明星"));
        // 连续/多余的空白项被丢掉
        assertEquals("[\"种子\",\"GUEST\"]", TPlayerServiceImpl.normalizeTags("种子, ,GUEST"));
    }

    @Test
    void validJsonArrayIsKeptVerbatim() {
        String json = "[\"种子\", \"GUEST\"]";
        assertEquals(json, TPlayerServiceImpl.normalizeTags(json));
    }

    /** [种子] 不是合法 JSON:不能原样写库(会 500),退化成按分隔符拆 */
    @Test
    void invalidJsonFallsBackToSplitting() {
        assertEquals("[\"[种子]\"]", TPlayerServiceImpl.normalizeTags("[种子]"));
    }
}
