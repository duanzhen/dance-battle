package org.dromara.common.sse.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.TournamentSseMessageDto;

/**
 * 赛事SSE工具类
 *
 * @author Lion Li
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TournamentSseMessageUtils {

    private final static Boolean SSE_ENABLE = SpringUtils.getProperty("sse.enabled", Boolean.class, true);
    private static TournamentSseEmitterManager MANAGER;

    static {
        if (isEnable() && MANAGER == null) {
            MANAGER = SpringUtils.getBean(TournamentSseEmitterManager.class);
        }
    }



    /**
     * 检查屏幕是否已注册
     *
     * @param screenId 屏幕ID
     * @return 是否已注册
     */
    public static boolean isScreenRegistered(String screenId) {
        if (!isEnable()) {
            return false;
        }
        return MANAGER.isScreenRegistered(screenId);
    }

    /**
     * 更新屏幕最后活跃时间
     *
     * @param screenId 屏幕ID
     */
    public static void updateScreenActivity(String screenId) {
        if (!isEnable()) {
            return;
        }
        MANAGER.updateScreenActivity(screenId);
    }

    // ========== 消息发送 ==========

    /**
     * 向指定屏幕的所有客户端（管理端+浏览端）发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public static void sendMessage(String screenId, String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.sendMessage(screenId, message);
    }

    /**
     * 向指定屏幕的所有管理端发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public static void sendMessageToManagers(String screenId, String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.sendMessageToManagers(screenId, message);
    }

    /**
     * 向指定屏幕的所有浏览端发送消息
     *
     * @param screenId 屏幕的唯一标识符
     * @param message  要发送的消息内容
     */
    public static void sendMessageToViewers(String screenId, String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.sendMessageToViewers(screenId, message);
    }

    /**
     * 向指定屏幕的指定终端发送消息
     *
     * @param screenId   屏幕的唯一标识符
     * @param terminalId 终端的唯一标识符
     * @param clientType 客户端类型（管理端/浏览端）
     * @param message    要发送的消息内容
     */
    public static void sendMessage(String screenId, String terminalId,
                                   TournamentSseEmitterManager.ClientType clientType, String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.sendMessage(screenId, terminalId, clientType, message);
    }

    /**
     * 向所有屏幕的所有客户端发送消息
     *
     * @param message 要发送的消息内容
     */
    public static void sendMessageToAll(String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.sendMessageToAll(message);
    }

    // ========== 连接断开 ==========

    /**
     * 断开指定屏幕的所有连接
     *
     * @param screenId 屏幕的唯一标识符
     */
    public static void disconnect(String screenId) {
        if (!isEnable()) {
            return;
        }
        MANAGER.disconnect(screenId);
    }

    /**
     * 断开指定屏幕的指定终端的连接
     *
     * @param screenId   屏幕的唯一标识符
     * @param terminalId 终端的唯一标识符
     * @param clientType 客户端类型
     */
    public static void disconnect(String screenId, String terminalId,
                                  TournamentSseEmitterManager.ClientType clientType) {
        if (!isEnable()) {
            return;
        }
        MANAGER.disconnect(screenId, terminalId, clientType);
    }

    // ========== 状态查询 ==========

    /**
     * 获取指定屏幕的浏览端在线数量
     *
     * @param screenId 屏幕ID
     * @return 在线数量
     */
    public static int getViewerCount(String screenId) {
        if (!isEnable()) {
            return 0;
        }
        return MANAGER.getViewerCount(screenId);
    }

    /**
     * 检查指定屏幕是否有浏览端在线
     *
     * @param screenId 屏幕ID
     * @return 是否有浏览端在线
     */
    public static boolean hasViewersOnline(String screenId) {
        if (!isEnable()) {
            return false;
        }
        return MANAGER.hasViewersOnline(screenId);
    }

    // ========== 分布式消息发送 ==========

    /**
     * 发布SSE订阅消息到指定屏幕
     *
     * @param tournamentSseMessageDto 要发布的SSE消息对象
     */
    public static void publishMessage(TournamentSseMessageDto tournamentSseMessageDto) {
        if (!isEnable()) {
            return;
        }
        MANAGER.publishMessage(tournamentSseMessageDto);
    }

    /**
     * 向所有屏幕发布订阅的消息(群发)
     *
     * @param message 要发布的消息内容
     */
    public static void publishAll(String message) {
        if (!isEnable()) {
            return;
        }
        MANAGER.publishAll(message);
    }

    /**
     * 向指定赛事的所有屏幕发送场景更新消息
     * 注意：会通知赛事下的所有屏幕，无论它们当前是否显示该场景
     * 这样可以确保屏幕在切换到该场景时显示的是最新数据
     *
     * @param tournamentId 赛事ID
     * @param sceneId      场景ID
     */
    public static void notifySceneUpdate(String tournamentId, String sceneId) {
        if (!isEnable()) {
            return;
        }
        MANAGER.notifySceneUpdate(tournamentId, sceneId);
    }

    /**
     * 向所有显示指定场景的屏幕发送场景更新消息
     * 注意：此方法会扫描所有Redis键，性能较差
     *
     * @param sceneId 场景ID
     * @deprecated 建议使用 {@link #notifySceneUpdate(String, String)} 并传入 tournamentId
     */
    @Deprecated
    public static void notifySceneUpdate(String sceneId) {
        if (!isEnable()) {
            return;
        }
        MANAGER.notifySceneUpdate(sceneId);
    }

    /**
     * 是否开启
     */
    public static Boolean isEnable() {
        return SSE_ENABLE;
    }

}
