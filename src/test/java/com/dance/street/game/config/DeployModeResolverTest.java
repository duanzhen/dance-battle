package com.dance.street.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证部署模式解析:JAR 默认 auto,native 可执行文件默认 standalone,显式配置始终优先。
 */
class DeployModeResolverTest {

    @Test
    void defaultsToAutoOutsideNativeImage() {
        assertFalse(DeployModeResolver.isNativeImage());
        assertEquals("auto", DeployModeResolver.resolve(new MockEnvironment()));
    }

    @Test
    void defaultsToStandaloneInsideNativeImage() {
        NativeImageFlag.runAsNativeImage(() -> {
            assertTrue(DeployModeResolver.isNativeImage());
            assertEquals("standalone", DeployModeResolver.resolve(new MockEnvironment()));
        });
    }

    @Test
    void blankDeployModeFallsBackToNativeDefault() {
        NativeImageFlag.runAsNativeImage(() ->
            assertEquals("standalone",
                DeployModeResolver.resolve(new MockEnvironment().withProperty("app.deploy-mode", ""))));
    }

    @Test
    void explicitModeWinsInsideNativeImage() {
        NativeImageFlag.runAsNativeImage(() -> {
            assertEquals("auto", DeployModeResolver.resolve(
                new MockEnvironment().withProperty("app.deploy-mode", "auto")));
            assertEquals("distributed", DeployModeResolver.resolve(
                new MockEnvironment().withProperty("app.deploy-mode", "distributed")));
        });
    }

    @Test
    void unknownModeFallsBackToAuto() {
        assertEquals("auto", DeployModeResolver.resolve(
            new MockEnvironment().withProperty("app.deploy-mode", "whatever")));
    }
}
