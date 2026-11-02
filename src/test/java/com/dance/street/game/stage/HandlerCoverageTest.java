package com.dance.street.game.stage;

import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfile.GeneratePolicy;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.service.impl.stage.handler.StageHandler;
import com.dance.street.game.service.impl.stage.handler.StageHandlerRegistry;
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
 * 每个 StageModeEnum 恰好有一个 StageHandler(替代原 Profiles/Settler uncoveredModes 门禁)。
 * 另断言 capabilities 可用,GENERATE 模式能产出 BracketPlan。
 */
@SpringBootTest
class HandlerCoverageTest {

    @Autowired
    private StageHandlerRegistry handlerRegistry;

    @Test
    void everyStageModeHasExactlyOneHandler() {
        List<String> uncovered = handlerRegistry.uncoveredModes();
        assertEquals(List.of(), uncovered, "每个赛制必须注册恰好一个 StageHandler: " + uncovered);
    }

    @Test
    void everyHandlerExposesCapabilities() {
        for (StageModeEnum mode : StageModeEnum.values()) {
            StageHandler handler = handlerRegistry.of(mode.getCode());
            StageModeProfile profile = handler.capabilities();
            assertNotNull(profile, mode.getCode());
            assertEquals(mode, profile.mode());
        }
    }

    @Test
    void generateModesCanBuildBracket() {
        for (StageModeEnum mode : StageModeEnum.values()) {
            StageHandler handler = handlerRegistry.of(mode.getCode());
            if (handler.capabilities().setup().generatePolicy() != GeneratePolicy.GENERATE) {
                assertThrows(ServiceException.class,
                    () -> handler.generateBracket(List.of(1L, 2L), null),
                    mode.getCode() + " 非 GENERATE 不应产出对阵树");
                continue;
            }
            BracketPlan plan = handler.generateBracket(List.of(1L, 2L, 3L, 4L), null);
            assertNotNull(plan, mode.getCode());
            assertTrue(plan.getMatches() != null && !plan.getMatches().isEmpty(),
                mode.getCode() + " 应对种子产出场次");
        }
    }

    @Test
    void unknownModeRejected() {
        assertThrows(ServiceException.class, () -> handlerRegistry.of("NOT_A_MODE"));
    }
}
