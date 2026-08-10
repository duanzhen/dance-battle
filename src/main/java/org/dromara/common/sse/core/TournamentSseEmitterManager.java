package org.dromara.common.sse.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.hutool.core.map.MapUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 赛事 SSE 管理器
 * 支持管理端和浏览端两种连接类型
 *
 * @author Lion Li
 */
@Slf4j
public class TournamentSseEmitterManager extends AbstractSseEmitterManager {

    /**
     * 订阅的频道
     */
    private final static String SSE_TOPIC = "global:sse:screen";

    /**
     * 屏幕场景映射 Redis key 前缀
     * key 格式: sse:screen:scene:{screenId}
     * value: sceneId
     */
    private final static String SCREEN_SCENE_KEY_PREFIX = "global:sse:screen:scene:";

    /**
     * 赛事屏幕列表 Redis key 前缀
     * key 格式: sse:tournament:screens:{tournamentId}
     * value: Set<screenId>
     */
    private final static String TOURNAMENT_SCREENS_KEY_PREFIX = "global:sse:tournament:screens:";

    /**
     * 屏幕注册表：记录哪些屏幕已被管理端注册
     * key: screenId, value: 最后活跃时间戳
     */
    private final static Map<String, Long> REGISTERED_SCREENS = new ConcurrentHashMap<>();

    /**
     * 屏幕超时时间（毫秒）默认 30 分钟无活动自动注销
     */
    private final static long SCREEN_TIMEOUT_MS = 30 * 60 * 1000;

    /**
     * 屏幕连接表
     * key: screenId
     * value: ScreenConnections（包含管理端和浏览端的连接）
     */
    private final static Map<String, ScreenConnections> SCREEN_EMITTERS = new ConcurrentHashMap<>();

    /**
     * 管理端多屏连接登记: terminalId -> 该连接订阅的所有屏幕
     * 用于一条 SSE 连接同时接收多个屏幕的消息(避免控制端每屏一条连接打满浏览器并发上限)
     */
    private final static Map<String, Set<String>> TERMINAL_SCREENS = new ConcurrentHashMap<>();

    /**
     * 屏幕连接信息
     */
    public static class ScreenConnections {
        /**
         * 管理端连接: terminalId -> SseEmitter
         */
        private final Map<String, SseEmitter> managers = new ConcurrentHashMap<>();

        /**
         * 浏览端连接: terminalId -> SseEmitter
         */
        private final Map<String, SseEmitter> viewers = new ConcurrentHashMap<>();

        public Map<String, SseEmitter> getManagers() {
            return managers;
        }

        public Map<String, SseEmitter> getViewers() {
            return viewers;
        }

        /**
         * 检查是否有任何浏览端在线
         */
        public boolean hasViewersOnline() {
            return MapUtil.isNotEmpty(viewers);
        }

        /**
         * 获取在线浏览端数量
         */
        public int getViewerCount() {
            return MapUtil.isEmpty(viewers) ? 0 : viewers.size();
        }

        /**
         * 检查是否有任何管理端在线
         */
        public boolean hasManagersOnline() {
            return MapUtil.isNotEmpty(managers);
        }

        /**
         * 检查是否没有任何连接
         */
        public boolean isEmpty() {
            return MapUtil.isEmpty(managers) && MapUtil.isEmpty(viewers);
        }
    }

    /**
     * 客户端类型枚举
     */
    public enum ClientType {
        MANAGER,  // 管理端
        VIEWER    // 浏览端
    }

    /**
     * 注册新屏幕或更新最后活跃时间（管理端可调用）
     * 如果屏幕已存在，则更新最后活跃时间
     *
     * @param screenId     屏幕ID
     * @param tournamentId 赛事ID（必填）
     * @return 是否注册成功（false表示已存在，只是更新了活跃时间）
     */
    public boolean registerScreen(String screenId, String tournamentId) {
        if (screenId == null || tournamentId == null) {
            return false;
        }

        boolean isNewRegistration = false;
        Long existingTime = REGISTERED_SCREENS.putIfAbsent(screenId, System.currentTimeMillis());
        if (existingTime != null) {
            // 屏幕已存在，更新最后活跃时间
            REGISTERED_SCREENS.put(screenId, System.currentTimeMillis());
        } else {
            isNewRegistration = true;
        }

        // 将屏幕添加到赛事的屏幕列表中（新注册时）
        if (isNewRegistration) {
            String tournamentScreensKey = TOURNAMENT_SCREENS_KEY_PREFIX + tournamentId;
            RedisUtils.setCacheSet(tournamentScreensKey, java.util.Collections.singleton(screenId));
            log.info("注册屏幕 screenId:{} tournamentId:{}", screenId, tournamentId);
        }

        return isNewRegistration;
    }

    /**
     * 检查屏幕是否已注册
     *
     * @param screenId 屏幕ID
     * @return 是否已注册
     */
    public boolean isScreenRegistered(String screenId) {
        return screenId != null && REGISTERED_SCREENS.containsKey(screenId);
    }

    /**
     * 更新屏幕最后活跃时间
     *
     * @param screenId 屏幕ID
     */
    public void updateScreenActivity(String screenId) {
        if (screenId != null && REGISTERED_SCREENS.containsKey(screenId)) {
            REGISTERED_SCREENS.put(screenId, System.currentTimeMillis());
        }
    }

    /**
     * 建立与指定屏幕的 SSE 连接
     *
     * @param screenId   逻辑屏幕的唯一标识符
     * @param terminalId 物理屏幕的唯一标识符
     * @param clientType 客户端类型（管理端/浏览端）
     * @return 返回一个 SseEmitter 实例，客户端可以通过该实例接收 SSE 事件
     * @throws IllegalStateException 如果屏幕未注册且是浏览端连接
     */
    public SseEmitter connect(String screenId, String terminalId, ClientType clientType) {
        // 浏览端连接时，检查屏幕是否已注册
        if (clientType == ClientType.VIEWER && !isScreenRegistered(screenId)) {
            throw new IllegalStateException("屏幕 " + screenId + " 未注册，无法建立浏览端连接");
        }

        // 更新屏幕最后活跃时间
        updateScreenActivity(screenId);

        // 获取或创建屏幕连接对象
        ScreenConnections connections = SCREEN_EMITTERS.computeIfAbsent(screenId, k -> new ScreenConnections());

        // 根据客户端类型选择对应的连接池
        Map<String, SseEmitter> emitters = clientType == ClientType.MANAGER
            ? connections.getManagers()
            : connections.getViewers();

        // 关闭该终端已存在的连接
        SseEmitter oldEmitter = emitters.remove(terminalId);
        if (oldEmitter != null) {
            oldEmitter.complete();
        }

        // 创建新的 SSE 连接
        SseEmitter emitter = new SseEmitter(86400000L);
        emitters.put(terminalId, emitter);

        // 设置连接回调
        emitter.onCompletion(() -> {
            emitters.remove(terminalId);
            checkAndCleanupScreen(screenId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(terminalId);
            checkAndCleanupScreen(screenId);
        });

        emitter.onError((e) -> {
            emitters.remove(terminalId);
            checkAndCleanupScreen(screenId);
        });

        try {
            // 向客户端发送连接成功事件
            emitter.send(SseEmitter.event().comment("connected"));

            // 如果是浏览端连接，发送当前场景ID并通知所有管理端有新的浏览端上线
            if (clientType == ClientType.VIEWER) {
                sendCurrentSceneToViewer(screenId, emitter);
                notifyManagerViewerStatus(screenId);
            }
        } catch (IOException e) {
            emitters.remove(terminalId);
        }

        return emitter;
    }

    /**
     * 管理端建立一条覆盖多个屏幕的 SSE 连接:
     * 同一个 emitter 登记到每个屏幕的管理端连接表,消息按 screenId 路由,
     * 关闭/超时/异常时从所有登记屏幕中清理。
     */
    public SseEmitter connectManagers(Collection<String> screenIds, String terminalId) {
        List<String> ids = screenIds.stream()
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            throw new IllegalStateException("screenIds 不能为空");
        }

        SseEmitter emitter = new SseEmitter(86400000L);
        TERMINAL_SCREENS.put(terminalId, new HashSet<>(ids));

        for (String screenId : ids) {
            ScreenConnections connections = SCREEN_EMITTERS.computeIfAbsent(screenId, k -> new ScreenConnections());
            SseEmitter old = connections.getManagers().put(terminalId, emitter);
            if (old != null && old != emitter) {
                try {
                    old.complete();
                } catch (Exception ignore) {
                    // 旧连接关闭失败忽略
                }
            }
        }

        emitter.onCompletion(() -> removeTerminal(terminalId));
        emitter.onTimeout(() -> removeTerminal(terminalId));
        emitter.onError(e -> removeTerminal(terminalId));

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            removeTerminal(terminalId);
        }
        return emitter;
    }

    /** 终端连接关闭:从该终端登记的所有屏幕中移除 */
    private void removeTerminal(String terminalId) {
        Set<String> screenIds = TERMINAL_SCREENS.remove(terminalId);
        if (screenIds == null) {
            return;
        }
        for (String screenId : screenIds) {
            ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
            if (connections != null) {
                connections.getManagers().remove(terminalId);
                checkAndCleanupScreen(screenId);
            }
        }
    }

    /**
     * 检查并清理屏幕连接（如果没有管理端和浏览端连接）
     */
    private void checkAndCleanupScreen(String screenId) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null && connections.isEmpty()) {
            SCREEN_EMITTERS.remove(screenId);
        }
    }

    /**
     * 通知管理端浏览端在线状态
     */
    private void notifyManagerViewerStatus(String screenId) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections == null || !connections.hasManagersOnline()) {
            return;
        }

        String statusMessage = buildViewerStatusMessage(screenId, connections);
        connections.getManagers().forEach((terminalId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("viewerStatus")
                    .data(statusMessage));
            } catch (Exception e) {
                // 发送失败，连接可能已断开
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 重复关闭忽略
                }
                connections.getManagers().remove(terminalId);
            }
        });
    }

    /**
     * 构建浏览端状态消息
     */
    private String buildViewerStatusMessage(String screenId, ScreenConnections connections) {
        return String.format("{\"screenId\":\"%s\",\"viewerCount\":%d,\"online\":true}",
            screenId, connections.getViewerCount());
    }

    /**
     * 向新连接的浏览端发送当前场景ID
     *
     * @param screenId 屏幕ID
     * @param emitter  浏览端的 SSE 连接
     */
    private void sendCurrentSceneToViewer(String screenId, SseEmitter emitter) {
        String currentSceneId = getCurrentScene(screenId);
        if (currentSceneId != null) {
            try {
                String message = String.format("{\"screenId\":\"%s\",\"sceneId\":\"%s\",\"type\":\"sceneSwitch\"}",
                    screenId, currentSceneId);
                emitter.send(SseEmitter.event().name("message").data(message));
                log.info("向浏览端发送当前场景 screenId:{} sceneId:{}", screenId, currentSceneId);
            } catch (IOException e) {
                log.error("向浏览端发送当前场景失败 screenId:{}", screenId, e);
            }
        }
    }

    /**
     * 设置屏幕当前显示的场景ID
     *
     * @param screenId 屏幕ID
     * @param sceneId  场景ID，为null时删除Redis中的映射
     */
    public void setCurrentScene(String screenId, String sceneId) {
        if (screenId == null) {
            return;
        }
        String redisKey = SCREEN_SCENE_KEY_PREFIX + screenId;
        if (sceneId == null) {
            RedisUtils.deleteObject(redisKey);
            log.info("删除屏幕场景映射 screenId:{}", screenId);
        } else {
            RedisUtils.setCacheObject(redisKey, sceneId);
            log.info("设置屏幕场景 screenId:{} sceneId:{}", screenId, sceneId);
        }
    }

    /**
     * 获取屏幕当前显示的场景ID
     *
     * @param screenId 屏幕ID
     * @return 场景ID，如果不存在则返回 null
     */
    public String getCurrentScene(String screenId) {
        if (screenId == null) {
            return null;
        }
        String redisKey = SCREEN_SCENE_KEY_PREFIX + screenId;
        return RedisUtils.getCacheObject(redisKey);
    }

    /**
     * 断开指定屏幕的所有连接
     *
     * @param screenId 屏幕的唯一标识符
     */
    public void disconnect(String screenId) {
        if (screenId == null) {
            return;
        }
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            disconnectAll(connections.getManagers());
            disconnectAll(connections.getViewers());
            SCREEN_EMITTERS.remove(screenId);
        }
        // 注意：这里不注销屏幕，屏幕会通过超时自动注销
    }

    /**
     * 断开指定屏幕的指定终端连接
     *
     * @param screenId   屏幕的唯一标识符
     * @param terminalId 终端的唯一标识符
     * @param clientType 客户端类型
     */
    public void disconnect(String screenId, String terminalId, ClientType clientType) {
        if (screenId == null || terminalId == null) {
            return;
        }
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            Map<String, SseEmitter> emitters = clientType == ClientType.MANAGER
                ? connections.getManagers()
                : connections.getViewers();

            SseEmitter emitter = emitters.remove(terminalId);
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event().comment("disconnected"));
                    emitter.complete();
                } catch (Exception ignore) {
                }
            }

            // 如果是浏览端断开，通知管理端状态变化
            if (clientType == ClientType.VIEWER) {
                notifyManagerViewerStatus(screenId);
            }

            checkAndCleanupScreen(screenId);
        }
    }

    /**
     * 断开所有连接
     */
    private void disconnectAll(Map<String, SseEmitter> emitters) {
        if (MapUtil.isEmpty(emitters)) {
            return;
        }
        emitters.forEach((terminalId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("disconnected"));
                emitter.complete();
            } catch (Exception ignore) {
            }
        });
        emitters.clear();
    }

    /**
     * 向指定屏幕的所有客户端发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public void sendMessage(String screenId, String message) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            // 向管理端和浏览端都发送
            sendMessageToGroup(connections.getManagers(), message);
            sendMessageToGroup(connections.getViewers(), message);
        }
    }

    /**
     * 向指定屏幕的管理端发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public void sendMessageToManagers(String screenId, String message) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            sendMessageToGroup(connections.getManagers(), message);
        }
    }

    /**
     * 向指定屏幕的浏览端发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public void sendMessageToViewers(String screenId, String message) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            sendMessageToGroup(connections.getViewers(), message);
        }
    }

    /**
     * 向指定屏幕的指定终端发送消息
     *
     * @param screenId   屏幕的唯一标识符
     * @param terminalId 终端的唯一标识符
     * @param clientType 客户端类型
     * @param message    要发送的消息内容
     */
    public void sendMessage(String screenId, String terminalId, ClientType clientType, String message) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        if (connections != null) {
            Map<String, SseEmitter> emitters = clientType == ClientType.MANAGER
                ? connections.getManagers()
                : connections.getViewers();

            SseEmitter emitter = emitters.get(terminalId);
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event()
                        .name("message")
                        .data(message));
                } catch (Exception e) {
                    emitter.complete();
                    emitters.remove(terminalId);
                }
            }
        }
    }

    /**
     * 向指定连接组发送消息
     */
    private void sendMessageToGroup(Map<String, SseEmitter> emitters, String message) {
        if (MapUtil.isEmpty(emitters)) {
            return;
        }
        emitters.entrySet().removeIf(entry -> {
            try {
                entry.getValue().send(SseEmitter.event()
                    .name("message")
                    .data(message));
                return false;
            } catch (Exception e) {
                entry.getValue().complete();
                return true;
            }
        });
    }

    /**
     * 向所有屏幕的所有客户端发送消息
     *
     * @param message 要发送的消息内容
     */
    public void sendMessageToAll(String message) {
        SCREEN_EMITTERS.forEach((screenId, connections) -> sendMessage(screenId, message));
    }

    /**
     * 获取已注册的屏幕列表
     */
    public Set<String> getRegisteredScreens() {
        return REGISTERED_SCREENS.keySet();
    }

    /**
     * 获取指定屏幕的浏览端在线数量
     */
    public int getViewerCount(String screenId) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        return connections == null ? 0 : connections.getViewerCount();
    }

    /**
     * 检查指定屏幕是否有浏览端在线
     */
    public boolean hasViewersOnline(String screenId) {
        ScreenConnections connections = SCREEN_EMITTERS.get(screenId);
        return connections != null && connections.hasViewersOnline();
    }

    /**
     * SSE 心跳检测，关闭无效连接并向管理端推送状态，同时注销超时屏幕
     */
    @Override
    protected void sseMonitor() {
        List<String> toRemoveScreen = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        SCREEN_EMITTERS.forEach((screenId, connections) -> {
            // 检查管理端连接
            checkAndSendHeartbeat(connections.getManagers());

            // 检查浏览端连接
            checkAndSendHeartbeat(connections.getViewers());

            // 如果没有任何连接，标记移除
            if (connections.isEmpty()) {
                toRemoveScreen.add(screenId);
            } else {
                // 向管理端发送浏览端在线状态
                if (connections.hasManagersOnline()) {
                    notifyManagerViewerStatus(screenId);
                }
            }
        });

        // 清理空屏幕
        toRemoveScreen.forEach(SCREEN_EMITTERS::remove);

        // 注销超时的屏幕
        cleanupExpiredScreens(currentTime);
    }

    /**
     * 清理超时的屏幕注册
     */
    private void cleanupExpiredScreens(long currentTime) {
        List<String> expiredScreens = new ArrayList<>();

        REGISTERED_SCREENS.forEach((screenId, lastActivityTime) -> {
            // 检查是否超时
            if (currentTime - lastActivityTime > SCREEN_TIMEOUT_MS) {
                expiredScreens.add(screenId);
            }
        });

        // 移除超时的屏幕
        expiredScreens.forEach(screenId -> {
            REGISTERED_SCREENS.remove(screenId);
            // 断开该屏幕的所有连接
            disconnect(screenId);
            log.info("屏幕 {} 已超时自动注销", screenId);
        });
    }

    /**
     * 检查并发送心跳
     */
    private void checkAndSendHeartbeat(Map<String, SseEmitter> emitters) {
        if (MapUtil.isEmpty(emitters)) {
            return;
        }
        emitters.entrySet().removeIf(entry -> !sendHeartbeat(entry.getValue()));
    }

    // ========== 分布式消息发送相关方法 ==========

    /**
     * 订阅SSE消息主题，并提供一个消费者函数来处理接收到的消息
     *
     * @param consumer 处理SSE消息的消费者函数
     */
    public void subscribeMessage(Consumer<TournamentSseMessageDto> consumer) {
        RedisUtils.subscribe(SSE_TOPIC, TournamentSseMessageDto.class, consumer);
    }

    /**
     * 发布SSE订阅消息到指定屏幕
     *
     * @param tournamentSseMessageDto 要发布的SSE消息对象
     */
    public void publishMessage(TournamentSseMessageDto tournamentSseMessageDto) {
        TournamentSseMessageDto broadcastMessage = new TournamentSseMessageDto();
        broadcastMessage.setMessage(tournamentSseMessageDto.getMessage());
        broadcastMessage.setScreenIds(tournamentSseMessageDto.getScreenIds());
        broadcastMessage.setTerminalIds(tournamentSseMessageDto.getTerminalIds());
        RedisUtils.publish(SSE_TOPIC, broadcastMessage, consumer -> {
            log.info("SSE发送主题订阅消息topic:{} screenIds:{} terminalIds:{} message:{}",
                SSE_TOPIC, tournamentSseMessageDto.getScreenIds(),
                tournamentSseMessageDto.getTerminalIds(), tournamentSseMessageDto.getMessage());
        });
    }

    /**
     * 向所有屏幕发布订阅的消息(群发)
     *
     * @param message 要发布的消息内容
     */
    public void publishAll(String message) {
        TournamentSseMessageDto broadcastMessage = new TournamentSseMessageDto();
        broadcastMessage.setMessage(message);
        RedisUtils.publish(SSE_TOPIC, broadcastMessage, consumer -> {
            log.info("SSE发送主题订阅消息topic:{} message:{}", SSE_TOPIC, message);
        });
    }

    /**
     * 向指定赛事的所有屏幕发送场景更新消息
     * 注意：会通知赛事下的所有屏幕，无论它们当前是否显示该场景
     * 这样可以确保屏幕在切换到该场景时显示的是最新数据
     *
     * @param tournamentId 赛事ID
     * @param sceneId      场景ID
     */
    public void notifySceneUpdate(String tournamentId, String sceneId) {
        if (tournamentId == null || sceneId == null) {
            return;
        }

        try {
            // 从Redis获取该赛事的所有活跃屏幕
            String tournamentScreensKey = TOURNAMENT_SCREENS_KEY_PREFIX + tournamentId;
            java.util.Collection<String> screenIds = RedisUtils.getCacheSet(tournamentScreensKey);

            if (screenIds == null || screenIds.isEmpty()) {
                log.debug("赛事 {} 没有活跃的屏幕", tournamentId);
                return;
            }

            // 向该赛事的所有屏幕发送场景更新消息
            String message = String.format("{\"sceneId\":\"%s\",\"type\":\"sceneUpdate\"}", sceneId);
            TournamentSseMessageDto sseMessage = new TournamentSseMessageDto();
            sseMessage.setMessage(message);
            sseMessage.setScreenIds(new java.util.ArrayList<>(screenIds));
            publishMessage(sseMessage);
            log.info("发送场景更新通知到赛事所有屏幕 tournamentId:{} sceneId:{} screenIds:{}", tournamentId, sceneId, screenIds);
        } catch (Exception e) {
            log.error("发送场景更新通知失败 tournamentId:{} sceneId:{}", tournamentId, sceneId, e);
        }
    }

    /**
     * 向所有显示指定场景的屏幕发送场景更新消息
     * 注意：此方法会扫描所有Redis键，性能较差，建议使用 notifySceneUpdate(tournamentId, sceneId)
     *
     * @param sceneId 场景ID
     * @deprecated 建议使用 {@link #notifySceneUpdate(String, String)} 并传入 tournamentId
     */
    @Deprecated
    public void notifySceneUpdate(String sceneId) {
        if (sceneId == null) {
            return;
        }

        // 通过Redis模式匹配查找所有显示该场景的屏幕
        String pattern = SCREEN_SCENE_KEY_PREFIX + "*";
        java.util.Set<String> screenIds = new java.util.HashSet<>();

        try {
            // 获取所有屏幕场景映射键
            java.util.Collection<String> keys = RedisUtils.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return;
            }

            // 检查每个键的值是否匹配目标场景ID
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                String currentSceneId = RedisUtils.getCacheObject(key);
                if (sceneId.equals(currentSceneId)) {
                    // 从key中提取screenId: sse:screen:scene:{screenId}
                    String screenId = key.substring(SCREEN_SCENE_KEY_PREFIX.length());
                    screenIds.add(screenId);
                }
            }
        } catch (Exception e) {
            log.error("扫描屏幕场景映射失败 sceneId:{}", sceneId, e);
        }

        // 向找到的所有屏幕发送场景更新消息
        if (!screenIds.isEmpty()) {
            String message = String.format("{\"sceneId\":\"%s\",\"type\":\"sceneUpdate\"}", sceneId);
            TournamentSseMessageDto sseMessage = new TournamentSseMessageDto();
            sseMessage.setMessage(message);
            sseMessage.setScreenIds(new java.util.ArrayList<>(screenIds));
            publishMessage(sseMessage);
            log.info("发送场景更新通知 sceneId:{} screenIds:{}", sceneId, screenIds);
        }
    }
}
