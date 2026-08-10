package org.dromara.common.sse.core;

import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.sse.dto.TournamentEventSseMessageDto;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 赛事事件 SSE 管理器。
 * 以 tournamentId 为维度维护长连接,通过 Redis 发布订阅实现多实例广播。
 * 裁判端复用同一通道:连接上标记 refereeId,消息带 refereeIds 时定向推送。
 * 统一 60s comment 心跳,避免空闲连接被代理/网关静默断开。
 *
 * @author duane
 */
@Slf4j
public class TournamentEventSseEmitterManager extends AbstractSseEmitterManager {

    /**
     * 订阅的频道
     */
    private static final String SSE_TOPIC = "global:sse:tournament-event";

    /**
     * 赛事连接表: tournamentId -> 连接集合
     */
    private static final Map<Long, Set<SseEmitter>> EMITTERS = new ConcurrentHashMap<>();

    /**
     * 连接 -> 裁判ID(普通连接无此记录,裁判连接记录后用于定向推送)
     */
    private static final Map<SseEmitter, Long> REFEREE_OF = new ConcurrentHashMap<>();

    /**
     * 建立普通赛事事件 SSE 连接(大屏/导播台/管理端)
     */
    public SseEmitter connect(Long tournamentId) {
        return doConnect(tournamentId, null);
    }

    /**
     * 建立裁判 SSE 连接(裁判端页面,复用赛事事件通道)
     */
    public SseEmitter connectReferee(Long tournamentId, Long refereeId) {
        return doConnect(tournamentId, refereeId);
    }

    private SseEmitter doConnect(Long tournamentId, Long refereeId) {
        SseEmitter emitter = new SseEmitter(86400000L);
        Set<SseEmitter> emitters = EMITTERS.computeIfAbsent(tournamentId, k -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        if (refereeId != null) {
            REFEREE_OF.put(emitter, refereeId);
        }

        emitter.onCompletion(() -> remove(tournamentId, emitter));
        emitter.onTimeout(() -> remove(tournamentId, emitter));
        emitter.onError(e -> remove(tournamentId, emitter));

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            remove(tournamentId, emitter);
        }
        log.info("赛事[{}]事件SSE连接建立({}),当前在线连接数:{}", tournamentId,
            refereeId != null ? "裁判" + refereeId : "普通", emitters.size());
        return emitter;
    }

    private void remove(Long tournamentId, SseEmitter emitter) {
        REFEREE_OF.remove(emitter);
        Set<SseEmitter> emitters = EMITTERS.get(tournamentId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            EMITTERS.remove(tournamentId);
        }
    }

    /**
     * 向指定赛事的全部连接推送消息
     */
    public void sendToTournament(Long tournamentId, String message) {
        Set<SseEmitter> emitters = EMITTERS.get(tournamentId);
        if (CollUtil.isEmpty(emitters)) {
            return;
        }
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("message").data(message));
                return false;
            } catch (Exception e) {
                emitter.complete();
                return true;
            }
        });
    }

    /**
     * 只向指定裁判的连接推送消息(赛事内定向,不影响大屏/导播台)
     */
    public void sendToReferees(Long tournamentId, List<Long> refereeIds, String message) {
        if (CollUtil.isEmpty(refereeIds)) {
            return;
        }
        Set<SseEmitter> emitters = EMITTERS.get(tournamentId);
        if (CollUtil.isEmpty(emitters)) {
            return;
        }
        Set<Long> targets = new HashSet<>(refereeIds);
        emitters.removeIf(emitter -> {
            Long rid = REFEREE_OF.get(emitter);
            if (rid == null || !targets.contains(rid)) {
                return false; // 非目标连接:保留
            }
            try {
                emitter.send(SseEmitter.event().name("message").data(message));
                return false;
            } catch (Exception e) {
                emitter.complete();
                return true;
            }
        });
    }

    /**
     * 心跳检测:发送 comment 保活并清理失效连接
     */
    @Override
    protected void sseMonitor() {
        List<Long> toRemove = new ArrayList<>();
        EMITTERS.forEach((tournamentId, emitters) -> {
            emitters.removeIf(emitter -> {
                if (!sendHeartbeat(emitter)) {
                    REFEREE_OF.remove(emitter);
                    return true;
                }
                return false;
            });
            if (emitters.isEmpty()) {
                toRemove.add(tournamentId);
            }
        });
        toRemove.forEach(EMITTERS::remove);
    }

    /**
     * 订阅 Redis 消息主题
     */
    public void subscribeMessage(Consumer<TournamentEventSseMessageDto> consumer) {
        RedisUtils.subscribe(SSE_TOPIC, TournamentEventSseMessageDto.class, consumer);
    }

    /**
     * 发布消息到 Redis(多实例广播)
     */
    public void publishMessage(TournamentEventSseMessageDto dto) {
        TournamentEventSseMessageDto broadcast = new TournamentEventSseMessageDto();
        broadcast.setTournamentId(dto.getTournamentId());
        broadcast.setMessage(dto.getMessage());
        broadcast.setRefereeIds(dto.getRefereeIds());
        RedisUtils.publish(SSE_TOPIC, broadcast, consumer -> {
            log.info("赛事事件SSE发布主题消息 topic:{} tournamentId:{} refereeIds:{} message:{}",
                SSE_TOPIC, dto.getTournamentId(), dto.getRefereeIds(), dto.getMessage());
        });
    }
}
