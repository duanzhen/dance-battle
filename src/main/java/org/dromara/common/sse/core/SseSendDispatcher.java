package org.dromara.common.sse.core;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * SSE 发送调度器:所有周期性写入(心跳、业务广播)都经此异步派发,业务线程不再阻塞在网络写上。
 *
 * <p><b>要解决的问题。</b>客户端(大屏、裁判手机、导播手机)一旦不可读——锁屏后 socket 半死、
 * 网络切换、进程被系统冻结——向它 write 会一直阻塞到 TCP 缓冲区被填满为止,而
 * {@code SseEmitter.send} 是同步写。此前所有发送都在调用者线程上执行,于是:</p>
 * <ul>
 *   <li>单机模式下调用者是提交事务的 HTTP 请求线程(经 {@code AfterCommitUtils} 提交后回调);</li>
 *   <li>分布式模式下调用者是 Redisson 的监听线程,阻塞它会拖慢同实例的其它 Redis 操作;</li>
 *   <li>心跳巡检也在同一个线程里逐个发,一条卡住的连接能把整轮巡检拖停。</li>
 * </ul>
 *
 * <p><b>怎么解决。</b>每个连接一条发送队列,同一连接的事件严格串行(不并发写同一个响应,
 * 也避免为一条慢连接堆积多个被阻塞的线程);发送在专用线程池里执行,调用者只入队立即返回;
 * 每次发送挂一个看门狗,超过 {@link #SEND_TIMEOUT_MS} 仍未返回就判定客户端不可读并主动
 * {@code complete} 掉该连接——断开回调会把连接从各管理器的连接表移除,客户端则由前端
 * 自动重连并做一次全量刷新补偿。积压超过 {@link #MAX_PENDING_PER_EMITTER} 或线程池拒绝
 * 任务时同样直接断开,用「丢一条连接」换取「不拖垮全局」。</p>
 *
 * <p>注意:各管理器在 {@code connect} 时同步发送的 {@code connected} 注释与当前场景
 * 不经过本类——那时响应可能尚未初始化(Spring 会把早期写入暂存),保持同步更稳妥。</p>
 *
 * @author duane
 */
@Slf4j
public final class SseSendDispatcher {

    private static final SseSendDispatcher INSTANCE = new SseSendDispatcher();

    /** 单次发送的最长允许时长:超过即判定客户端不可读,主动断开该连接 */
    private static final long SEND_TIMEOUT_MS = 10_000L;

    /** 单条连接允许积压的事件数:再超说明对端已经跟不上,断开而不是无限占用内存 */
    private static final int MAX_PENDING_PER_EMITTER = 64;

    /** 发送线程池队列容量:满了直接拒绝(断开),不阻塞调用者 */
    private static final int SEND_QUEUE_CAPACITY = 1024;

    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService watchdog;

    /** 连接 -> 该连接的发送队列 */
    private final Map<SseEmitter, EmitterQueue> queues = new ConcurrentHashMap<>();

    private SseSendDispatcher() {
        int cores = Runtime.getRuntime().availableProcessors();
        // 核心线程常驻、上限按核数放大:正常情况几条线程轮转即可;
        // 慢连接会占住线程,但受看门狗与队列上限约束,不会无限堆积
        this.pool = new ThreadPoolExecutor(
            Math.max(2, Math.min(cores, 4)),
            Math.max(4, cores * 2),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
            new BasicThreadFactory.Builder().daemon(true).namingPattern("sse-send-%d").build(),
            new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = new ScheduledThreadPoolExecutor(1,
            new BasicThreadFactory.Builder().daemon(true).namingPattern("sse-watchdog-%d").build());
    }

    public static SseSendDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * 把一次事件投递到指定连接。**永不阻塞调用者**:入队即返回。
     *
     * <p>事件按需为每个连接单独构造({@code SseEmitter.event()...}):复用同一个 builder
     * 会依赖 Spring 内部缓冲的重置细节,不值当。</p>
     *
     * @param emitter 目标连接
     * @param event   事件
     */
    public void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        if (emitter == null || event == null) {
            return;
        }
        EmitterQueue queue = queues.computeIfAbsent(emitter, k -> new EmitterQueue());
        boolean overflow = false;
        boolean start = false;
        synchronized (queue) {
            if (queue.closed) {
                return;
            }
            if (queue.running) {
                if (queue.pending.size() >= MAX_PENDING_PER_EMITTER) {
                    queue.closed = true;
                    overflow = true;
                } else {
                    queue.pending.add(event);
                }
            } else {
                queue.running = true;
                queue.current = event;
                start = true;
            }
        }
        if (overflow) {
            log.warn("SSE 连接积压已达上限 {} 条,判定客户端不可读并断开(客户端会自动重连并全量补偿)",
                MAX_PENDING_PER_EMITTER);
            queues.remove(emitter, queue);
            closeQuietly(emitter);
            return;
        }
        if (start) {
            submit(emitter, queue);
        }
    }

    /**
     * 连接已从业务侧连接表移除:清掉发送队列,避免残留对象长期占用。
     * 由各管理器的关闭/超时/异常回调调用;重复调用安全。
     */
    public void discard(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        EmitterQueue queue = queues.remove(emitter);
        if (queue != null) {
            synchronized (queue) {
                queue.closed = true;
                queue.pending.clear();
                queue.current = null;
            }
        }
    }

    private void submit(SseEmitter emitter, EmitterQueue queue) {
        try {
            pool.execute(() -> runLoop(emitter, queue));
        } catch (RejectedExecutionException e) {
            // 发送池已满:不阻塞调用者,丢掉这条连接自保。客户端侧有重连 + 全量刷新,
            // 少一条连接不会造成状态不一致,而阻塞调用者会拖垮整个实例
            log.warn("SSE 发送线程池已满,断开该连接自保(客户端会自动重连并全量补偿)");
            fail(emitter, queue);
        }
    }

    /** 串行消费一条连接的发送队列:同一连接的事件按入队顺序依次写出 */
    private void runLoop(SseEmitter emitter, EmitterQueue queue) {
        while (true) {
            SseEmitter.SseEventBuilder event;
            synchronized (queue) {
                event = queue.current;
            }
            if (event == null || !doSend(emitter, event)) {
                fail(emitter, queue);
                return;
            }
            synchronized (queue) {
                queue.current = queue.pending.poll();
                if (queue.current == null) {
                    queue.running = false;
                    return;
                }
            }
        }
    }

    /**
     * 执行一次写入,并用看门狗兜住「写不进去又不报错」的半死连接。
     *
     * <p>看门狗与正常返回用同一个 {@link AtomicBoolean} 竞争:谁先置位谁生效。
     * 正常返回后看门狗即使已经被调度也不会再关连接;反之看门狗先判定超时并关掉连接,
     * 阻塞中的写入会因此报错,由调用方走失败清理。</p>
     *
     * @return true=本次写入成功
     */
    private boolean doSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        AtomicBoolean settled = new AtomicBoolean(false);
        ScheduledFuture<?> watchdogTask = watchdog.schedule(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            log.warn("SSE 单次发送超过 {}ms 未返回,判定客户端不可读并主动断开连接(客户端会自动重连)",
                SEND_TIMEOUT_MS);
            closeQuietly(emitter);
        }, SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            emitter.send(event);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            settled.set(true);
            watchdogTask.cancel(false);
        }
    }

    /** 发送失败/超时/积压:清队列、摘记录、关连接 */
    private void fail(SseEmitter emitter, EmitterQueue queue) {
        synchronized (queue) {
            queue.closed = true;
            queue.pending.clear();
            queue.current = null;
            queue.running = false;
        }
        queues.remove(emitter, queue);
        closeQuietly(emitter);
    }

    private static void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 已完成/重复关闭:忽略
        }
    }

    /** 单条连接的发送队列(仅被 {@code synchronized(queue)} 保护) */
    private static final class EmitterQueue {
        private final ArrayDeque<SseEmitter.SseEventBuilder> pending = new ArrayDeque<>();
        private SseEmitter.SseEventBuilder current;
        private boolean running;
        private boolean closed;
    }
}
