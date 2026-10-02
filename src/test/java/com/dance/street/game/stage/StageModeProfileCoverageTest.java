package com.dance.street.game.stage;

import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfiles;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 赛制画像覆盖自检:每种 stageMode 都必须有 profile,漏配直接红。
 * 纯数据表,不需要 Spring/DB。
 */
class StageModeProfileCoverageTest {

    @Test
    void everyStageModeHasProfile() {
        assertEquals(List.of(), StageModeProfiles.uncoveredModes(),
            "有赛制缺少画像配置,新增赛制时请在 StageModeProfiles 里补一行");
    }

    @Test
    void profileLookupWorksForEveryMode() {
        for (StageModeEnum mode : StageModeEnum.values()) {
            StageModeProfile profile = StageModeProfiles.of(mode.getCode());
            assertNotNull(profile, "缺少画像: " + mode.getCode());
            assertEquals(mode, profile.mode());
        }
    }

    @Test
    void unknownModeIsRejected() {
        assertThrows(ServiceException.class, () -> StageModeProfiles.of("NOT_A_MODE"));
    }
}
