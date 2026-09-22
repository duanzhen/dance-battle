package org.dromara.common.sse.core;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 发送调度器的核心保证:派发不阻塞调用者、读不动的连接会被主动断开。
 *
 * <p>这两条是"一个大屏/一部锁屏手机拖垮整个实例推送"的防线,回退会直接表现为
 * 现场推送整体变慢甚至卡死,因此用测试钉住。</p>
 */
@Tag("local")
class SseSendDispatcherTest {

    /**
     * 可人为阻塞/放行的 SseEmitter:模拟"锁屏手机 / 断网大屏"这类写不进去又不报错的客户端。
     */
    private static class BlockingEmitter extends SseEmitter {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        BlockingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (completed.get()) {
                throw new IOException("连接已被断开");
            }
        }

        @Override
        public void complete() {
            completed.set(true);
            super.complete();
        }

        void releaseNow() {
            release.countDown();
        }
    }

    @Test
    void sendDoesNotBlockCallerEvenWhenClientIsStuck() throws Exception {
        BlockingEmitter emitter = new BlockingEmitter();
        try {
            long start = System.nanoTime();
            SseSendDispatcher.getInstance().send(emitter,
                SseEmitter.event().name("message").data("{\"type\":\"stage\"}"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            // 客户端已经卡在写里(发送线程进入阻塞),调用者仍应立即返回
            assertTrue(emitter.entered.await(2, TimeUnit.SECONDS), "发送任务应已在发送线程上执行");
            assertTrue(elapsedMs < 500, "派发不得阻塞调用者,实测 " + elapsedMs + "ms");
        } finally {
            emitter.releaseNow();
            SseSendDispatcher.getInstance().discard(emitter);
        }
    }

    @Test
    void excessiveBacklogDisconnectsStuckClientInsteadOfGrowingForever() throws Exception {
        BlockingEmitter emitter = new BlockingEmitter();
        SseSendDispatcher dispatcher = SseSendDispatcher.getInstance();
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("message").data("{\"type\":\"stage\"}");
        try {
            // 第一条进入发送中并阻塞,其余进入该连接的发送队列;超过上限后应直接断开
            for (int i = 0; i < 300; i++) {
                dispatcher.send(emitter, event);
            }
            long deadline = System.currentTimeMillis() + 2_000L;
            while (!emitter.completed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(emitter.completed.get(), "积压超过上限时应主动断开该连接,而不是无限占用内存");
        } finally {
            emitter.releaseNow();
            dispatcher.discard(emitter);
        }
    }
}
