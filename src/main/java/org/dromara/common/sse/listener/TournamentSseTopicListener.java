package org.dromara.common.sse.listener;

import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * 赛事SSE 主题订阅监听器
 *
 * @author Lion Li
 */
@Slf4j
public class TournamentSseTopicListener implements ApplicationRunner, Ordered {

    @Autowired
    private TournamentSseEmitterManager tournamentSseEmitterManager;

    /**
     * 在Spring Boot应用程序启动时初始化赛事SSE主题订阅监听器
     *
     * @param args 应用程序参数
     * @throws Exception 初始化过程中可能抛出的异常
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        tournamentSseEmitterManager.subscribeMessage((message) -> {
            log.info("赛事SSE主题订阅收到消息screenIds={} terminalIds={} message={}",
                message.getScreenIds(), message.getTerminalIds(), message.getMessage());

            // 如果screenIds不为空就按screenIds发消息，如果为空就群发
            if (CollUtil.isNotEmpty(message.getScreenIds())) {
                // 遍历每个屏幕ID
                message.getScreenIds().forEach(screenId -> {
                    tournamentSseEmitterManager.sendMessage(screenId, message.getMessage());
                });
            } else {
                // 如果screenIds为空，向所有屏幕的所有终端发送消息
                tournamentSseEmitterManager.sendMessageToAll(message.getMessage());
            }
        });
        log.info("初始化赛事SSE主题订阅监听器成功");
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
