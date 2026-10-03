package com.dance.street.game.engine.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 海选圈展示名:A圈/B圈…Z圈/AA圈,内部 ZONE-k 不变。 */
class CircleLabelTest {

    @Test
    void circleLabelUsesLetters() {
        assertEquals("A圈", StageFlowSupport.circleLabel(1));
        assertEquals("B圈", StageFlowSupport.circleLabel(2));
        assertEquals("Z圈", StageFlowSupport.circleLabel(26));
        assertEquals("AA圈", StageFlowSupport.circleLabel(27));
        assertEquals("A圈", StageFlowSupport.circleLabel(0));
    }

    @Test
    void circleNameMatchesCircleLabel() {
        assertEquals("A圈", StageFlowSupport.circleName(1, 1));
        assertEquals("C圈", StageFlowSupport.circleName(4, 3));
    }

    @Test
    void circleZoneStaysNumericKey() {
        assertEquals("ZONE-1", StageFlowSupport.circleZone(1));
        assertEquals("ZONE-4", StageFlowSupport.circleZone(4));
    }
}
