package com.dance.street.game.engine.common;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老库 {@code t_stage.roster_config_json} 里的来源组 JSON 编解码:启动迁移时用它把历史规则搬进边表。
 */
class StageRosterGroupCodecTest {

    @Test
    void parseReturnsEmptyWhenConfigMissingOrGroupsNotArray() {
        assertTrue(StageRosterGroupCodec.parse(null).isEmpty());
        assertTrue(StageRosterGroupCodec.parse("").isEmpty());
        assertTrue(StageRosterGroupCodec.parse("   ").isEmpty());
        assertTrue(StageRosterGroupCodec.parse("{}").isEmpty(), "没有 groups 字段应返回空");
        assertTrue(StageRosterGroupCodec.parse("{\"groups\":null}").isEmpty());
        assertTrue(StageRosterGroupCodec.parse("{\"groups\":{}}").isEmpty(), "groups 不是数组应返回空");
    }

    @Test
    void parseMapsAllKnownFieldsAndIgnoresRetiredPriority() {
        String json = """
            {"groups":[
              {"sourceStageId":42,"resultFilter":"ADVANCE","zone":"A","rankStart":1,"rankEnd":8,
               "rankByZone":true,"round":2,"scoreMin":10.5,"scoreMax":99.25,
               "fillMode":"AUTO","quota":4,"priority":9,"orderBy":"ZONE_RANK"}
            ]}""";
        List<TStageRosterGroupBo> groups = StageRosterGroupCodec.parse(json);
        assertEquals(1, groups.size());
        TStageRosterGroupBo g = groups.get(0);
        assertEquals(42L, g.getSourceStageId());
        assertEquals("ADVANCE", g.getResultFilter());
        assertEquals("A", g.getZone());
        assertEquals(1, g.getRankStart());
        assertEquals(8, g.getRankEnd());
        assertTrue(g.getRankByZone());
        assertEquals(2, g.getRound());
        assertEquals(0, new BigDecimal("10.5").compareTo(g.getScoreMin()));
        assertEquals(0, new BigDecimal("99.25").compareTo(g.getScoreMax()));
        assertEquals("AUTO", g.getFillMode());
        assertEquals(4, g.getQuota());
        assertEquals("ZONE_RANK", g.getOrderBy());
    }

    @Test
    void parseKeepsEmptyGroupWithNullOptionalFields() {
        List<TStageRosterGroupBo> groups = StageRosterGroupCodec.parse("{\"groups\":[{},{\"sourceStageId\":7}]}");
        assertEquals(2, groups.size(), "多个组都要解析出来");
        assertNull(groups.get(0).getSourceStageId());
        assertNull(groups.get(0).getResultFilter());
        assertNull(groups.get(0).getRankStart());
        assertEquals(7L, groups.get(1).getSourceStageId());
    }

    @Test
    void parseThrowsOnCorruptJson() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> StageRosterGroupCodec.parse("{not json"));
        assertTrue(e.getMessage().contains("配置损坏"), "损坏配置应给出可读错误:" + e.getMessage());
    }
}
