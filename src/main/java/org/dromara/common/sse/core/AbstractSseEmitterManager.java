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
     * 向单个连接发送心跳(命名事件 ping)。
     *
     * <p>用命名事件而不是 comment:comment 不会触发浏览器的 onmessage,
     * 客户端无法据此判断"连接还活着",会误判空闲而反复强制重连(表现为页面周期性闪烁)。
     * 命名事件客户端可按需监听(只更新存活时间,不触发业务刷新)。</p>
     *
     * @return true=发送成功;false=连接已失效(已 complete,调用方应移除)
     */
    protected boolean sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("ping").data("{}"));
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
