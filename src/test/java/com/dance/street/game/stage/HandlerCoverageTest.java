package com.dance.street.game.stage;

import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.service.impl.stage.StageMode;
import com.dance.street.game.service.impl.stage.StageModes;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个赛制恰好有一个 StageMode。会建树的三种能产出 BracketPlan,其余调用 plan 应拒绝。
 */
@SpringBootTest
class HandlerCoverageTest {

    @Autowired
    private StageModes stageModes;

    @Test
    void everyStageModeHasExactlyOneMode() {
        List<String> uncovered = stageModes.uncoveredModes();
        assertEquals(List.of(), uncovered, "每个赛制必须注册恰好一个 StageMode: " + uncovered);
    }

    @Test
    void generateModesCanBuildBracket() {
        for (StageModeEnum mode : StageModeEnum.values()) {
            StageMode impl = stageModes.of(mode.getCode());
            assertEquals(mode, impl.mode());
            if (!mode.generatesBracket()) {
                assertThrows(ServiceException.class,
                    () -> impl.plan(List.of(1L, 2L), null),
                    mode.getCode() + " 不生成对阵");
                continue;
            }
            BracketPlan plan = impl.plan(List.of(1L, 2L, 3L, 4L), null);
            assertNotNull(plan, mode.getCode());
            assertTrue(plan.getMatches() != null && !plan.getMatches().isEmpty(),
                mode.getCode() + " 应对种子产出场次");
        }
    }

    @Test
    void unknownModeRejected() {
        assertThrows(ServiceException.class, () -> stageModes.of("NOT_A_MODE"));
    }
}
