package org.dromara.common.sse.core;

import org.dromara.common.core.utils.SpringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 管理器公共基类:统一 60s comment 心跳调度与发送逻辑。
 * 各通道(SseEmitterManager/TournamentSseEmitterManager/TournamentEventSseEmitterManager)
 * 继承本类,只需实现 sseMonitor 遍历自己的连接表,心跳发送/失败清理完全一致。
 *
 * @author duane
 */
@Slf4j
public abstract class AbstractSseEmitterManager {

    /** 心跳间隔(秒):所有 SSE 通道统一,避免空闲连接被代理/网关静默断开 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60L;

    protected AbstractSseEmitterManager() {
        SpringUtils.getBean(ScheduledExecutorService.class)
            .scheduleWithFixedDelay(this::safeMonitor,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 心跳调度入口:把 {@link #sseMonitor()} 包在 try/catch 里。
     *
     * <p>{@code scheduleWithFixedDelay} 的语义是「任务抛出未捕获异常后,后续执行被永久抑制」——
     * 一旦巡检被某次异常打断,保活与失效连接清理就此停摆,而现场只能看到一行 ERROR 日志,
     * 很难联想到"心跳已经死了"。这里兜住所有异常,保证巡检永远能进入下一轮。</p>
     */
    private void safeMonitor() {
        try {
            sseMonitor();
        } catch (Throwable t) {
            log.error("SSE 心跳巡检异常,已跳过本轮(不影响后续心跳)", t);
        }
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
     * <p>发送经 {@link SseSendDispatcher} 异步派发:巡检线程不做网络写,不会被一条
     * 读不动的连接(锁屏手机、断网大屏)拖住整轮巡检;对端确实不可读时,由发送看门狗
     * 判定超时并断开连接,断开回调负责把它从连接表移除。</p>
     */
    protected void sendHeartbeat(SseEmitter emitter) {
        SseSendDispatcher.getInstance().send(emitter, SseEmitter.event().name("ping").data("{}"));
    }
}
