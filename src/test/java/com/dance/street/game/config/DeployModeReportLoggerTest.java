package com.dance.street.game.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署形态自检:显式要求分布式却没有 Redis 客户端时必须启动失败,
 * 避免 native 产物(构建期已裁剪掉 Redis 自动装配)被误配成 distributed 后静默按单机跑。
 */
class DeployModeReportLoggerTest {

    @Test
    void distributedWithoutRedisFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> DeployModeReportLogger.assertDistributedHasRedis(
                DeployModeResolver.DISTRIBUTED, false));
        assertTrue(e.getMessage().contains("distributed"));
    }

    @Test
    void distributedWithRedisPasses() {
        assertDoesNotThrow(() -> DeployModeReportLogger.assertDistributedHasRedis(
            DeployModeResolver.DISTRIBUTED, true));
    }

    @Test
    void singleMachineModesDoNotRequireRedis() {
        assertDoesNotThrow(() -> DeployModeReportLogger.assertDistributedHasRedis(
            DeployModeResolver.STANDALONE, false));
        assertDoesNotThrow(() -> DeployModeReportLogger.assertDistributedHasRedis(
            DeployModeResolver.AUTO, false));
    }
}
