package org.dromara.common.sse.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.sse.dto.SseMessageDto;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 管理 Server-Sent Events (SSE) 连接
 *
 * @author Lion Li
 */
@Slf4j
public class SseEmitterManager extends AbstractSseEmitterManager {

    /**
     * 订阅的频道
     */
    private final static String SSE_TOPIC = "global:sse";

    private final static Map<Long, Map<String, SseEmitter>> USER_TOKEN_EMITTERS = new ConcurrentHashMap<>();

    /**
     * 建立与指定用户的 SSE 连接
     *
     * @param userId 用户的唯一标识符，用于区分不同用户的连接
     * @param token  用户的唯一令牌，用于识别具体的连接
     * @return 返回一个 SseEmitter 实例，客户端可以通过该实例接收 SSE 事件
     */
    public SseEmitter connect(Long userId, String token) {
        // 从 USER_TOKEN_EMITTERS 中获取或创建当前用户的 SseEmitter 映射表（ConcurrentHashMap）
        // 每个用户可以有多个 SSE 连接，通过 token 进行区分
        Map<String, SseEmitter> emitters = USER_TOKEN_EMITTERS.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        // 创建一个新的 SseEmitter 实例，超时时间设置为一天 避免连接之后直接关闭浏览器导致连接停滞
        SseEmitter emitter = new SseEmitter(86400000L);

        // 同一 token 重连:先登记新连接再关闭旧连接;回调按 emitter 身份移除,
        // 避免旧连接的关闭回调把刚登记的新连接从映射表里删掉(会表现为连接在但收不到消息)
        SseEmitter oldEmitter = emitters.put(token, emitter);

        // 当 emitter 完成、超时或发生错误时，从映射表中移除对应的 token(并回收其发送队列)
        emitter.onCompletion(() -> removeEmitter(emitters, token, emitter));
        emitter.onTimeout(() -> removeEmitter(emitters, token, emitter));
        emitter.onError((e) -> removeEmitter(emitters, token, emitter));

        if (oldEmitter != null && oldEmitter != emitter) {
            try {
                // 关闭旧连接,防止超过最大连接数
                oldEmitter.complete();
            } catch (Exception ignore) {
                // 旧连接已关闭时忽略
            }
        }

        try {
            // 向客户端发送一条连接成功的事件
            emitter.send(SseEmitter.event().comment("connected"));
            // 新连接立即补一次心跳:前端以"最近 20s 内有心跳"判定已连上,不补的话要等下一个巡检周期(最长 15s)才变绿
            sendHeartbeat(emitter);
        } catch (IOException e) {
            // 如果发送消息失败，则从映射表中移除 emitter
            removeEmitter(emitters, token, emitter);
        }
        return emitter;
    }

    /** 从连接表移除并回收发送队列(统一出口,避免发送队列残留) */
    private void removeEmitter(Map<String, SseEmitter> emitters, String token, SseEmitter emitter) {
        emitters.remove(token, emitter);
        SseSendDispatcher.getInstance().discard(emitter);
    }

    /**
     * 断开指定用户的 SSE 连接
     *
     * @param userId 用户的唯一标识符，用于区分不同用户的连接
     * @param token  用户的唯一令牌，用于识别具体的连接
     */
    public void disconnect(Long userId, String token) {
        if (userId == null || token == null) {
            return;
        }
        Map<String, SseEmitter> emitters = USER_TOKEN_EMITTERS.get(userId);
        if (MapUtil.isNotEmpty(emitters)) {
            // 只关闭连接,不再同步写 "disconnected" 注释:那会在调用线程(/sse/close 的 HTTP 线程)
            // 上做网络写,对端锁屏/断网时会阻塞到 TCP 缓冲填满;而 comment 不触发浏览器 onmessage,
            // 客户端本就感知不到这条注释。关闭后由 onCompletion 回调摘除连接并回收发送队列。
            SseEmitter sseEmitter = emitters.remove(token);
            if (sseEmitter != null) {
                try {
                    sseEmitter.complete();
                } catch (Exception ignore) {
                    // 已完成/重复关闭:忽略
                }
            }
        } else {
            USER_TOKEN_EMITTERS.remove(userId);
        }
    }

    /**
     * SSE 心跳检测，关闭无效连接
     */
    @Override
    protected void sseMonitor() {
        // 记录需要移除的用户ID
        List<Long> toRemoveUsers = new ArrayList<>();

        USER_TOKEN_EMITTERS.forEach((userId, emitterMap) -> {
            if (CollUtil.isEmpty(emitterMap)) {
                toRemoveUsers.add(userId);
                return;
            }

            // 心跳异步派发:巡检线程不做网络写;不可读的连接由发送看门狗判定超时并断开,
            // 断开回调(removeEmitter)负责把它从连接表移除
            emitterMap.values().forEach(this::sendHeartbeat);

            // 移除空连接用户
            if (emitterMap.isEmpty()) {
                toRemoveUsers.add(userId);
            }
        });

        // 循环结束后统一清理空用户，避免并发修改异常
        toRemoveUsers.forEach(USER_TOKEN_EMITTERS::remove);
    }

    /**
     * 订阅SSE消息主题，并提供一个消费者函数来处理接收到的消息
     *
     * @param consumer 处理SSE消息的消费者函数
     */
    public void subscribeMessage(Consumer<SseMessageDto> consumer) {
        RedisUtils.subscribe(SSE_TOPIC, SseMessageDto.class, consumer);
    }

    /**
     * 向指定的用户会话发送消息
     *
     * @param userId  要发送消息的用户id
     * @param message 要发送的消息内容
     */
    public void sendMessage(Long userId, String message) {
        Map<String, SseEmitter> emitters = USER_TOKEN_EMITTERS.get(userId);
        if (MapUtil.isEmpty(emitters)) {
            USER_TOKEN_EMITTERS.remove(userId);
            return;
        }
        // 异步派发:调用方(业务线程/Redis 监听线程)不再阻塞在网络写上;
        // 发送失败或超时的连接由断开回调从连接表移除
        emitters.values().forEach(emitter -> SseSendDispatcher.getInstance()
            .send(emitter, SseEmitter.event().name("message").data(message)));
    }

    /**
     * 本机全用户会话发送消息
     *
     * @param message 要发送的消息内容
     */
    public void sendMessage(String message) {
        for (Long userId : USER_TOKEN_EMITTERS.keySet()) {
            sendMessage(userId, message);
        }
    }

    /**
     * 发布SSE订阅消息
     *
     * @param sseMessageDto 要发布的SSE消息对象
     */
    public void publishMessage(SseMessageDto sseMessageDto) {
        SseMessageDto broadcastMessage = new SseMessageDto();
        broadcastMessage.setMessage(sseMessageDto.getMessage());
        broadcastMessage.setUserIds(sseMessageDto.getUserIds());
        RedisUtils.publish(SSE_TOPIC, broadcastMessage, consumer -> {
            log.info("SSE发送主题订阅消息topic:{} session keys:{} message:{}",
                SSE_TOPIC, sseMessageDto.getUserIds(), sseMessageDto.getMessage());
        });
    }

    /**
     * 向所有的用户发布订阅的消息(群发)
     *
     * @param message 要发布的消息内容
     */
    public void publishAll(String message) {
        SseMessageDto broadcastMessage = new SseMessageDto();
        broadcastMessage.setMessage(message);
        RedisUtils.publish(SSE_TOPIC, broadcastMessage, consumer -> {
            log.info("SSE发送主题订阅消息topic:{} message:{}", SSE_TOPIC, message);
        });
    }
}
