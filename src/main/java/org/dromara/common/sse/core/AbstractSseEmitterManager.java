package org.dromara.common.sse.core;

import org.dromara.common.core.utils.SpringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 管理器公共基类:统一 60s comment 心跳调度与发送逻辑。
 * 各通道(SseEmitterManager/TournamentSseEmitterManager/TournamentEventSseEmitterManager)
 * 继承本类,只需实现 sseMonitor 遍历自己的连接表,心跳发送/失败清理完全一致。
 *
 * @author duane
 */
public abstract class AbstractSseEmitterManager {

    /** 心跳间隔(秒):所有 SSE 通道统一,避免空闲连接被代理/网关静默断开 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60L;

    protected AbstractSseEmitterManager() {
        SpringUtils.getBean(ScheduledExecutorService.class)
            .scheduleWithFixedDelay(this::sseMonitor,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 心跳检测:遍历本通道的连接表,对每个连接发送 comment 心跳,失效连接 complete 并移除。
     * 子类按各自的连接结构实现。
     */
    protected abstract void sseMonitor();

    /**
     * 向单个连接发送心跳 comment。
     *
     * @return true=发送成功;false=连接已失效(已 complete,调用方应移除)
     */
    protected boolean sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
            return true;
        } catch (Exception e) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 重复关闭忽略
            }
            return false;
        }
    }
}
