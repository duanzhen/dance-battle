package org.dromara.common.sse.listener;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.dromara.common.sse.dto.TournamentEventSseMessageDto;
import cn.hutool.core.collection.CollUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * 赛事事件SSE主题订阅监听器
 *
 * @author duane
 */
@Slf4j
public class TournamentEventSseTopicListener implements ApplicationRunner, Ordered {

    @Autowired
    private TournamentEventSseEmitterManager tournamentEventSseEmitterManager;

    @Override
    public void run(ApplicationArguments args) {
        tournamentEventSseEmitterManager.subscribeMessage(message -> {
            log.info("赛事事件SSE主题订阅收到消息 tournamentId={} refereeIds={} message={}",
                message.getTournamentId(), message.getRefereeIds(), message.getMessage());
            if (CollUtil.isNotEmpty(message.getRefereeIds())) {
                // 裁判定向事件:只推送给该赛事内命中的裁判连接
                tournamentEventSseEmitterManager.sendToReferees(
                    message.getTournamentId(), message.getRefereeIds(), message.getMessage());
            } else {
                // 普通赛事事件:广播给赛事全部连接
                tournamentEventSseEmitterManager.sendToTournament(
                    message.getTournamentId(), message.getMessage());
            }
        });
        log.info("初始化赛事事件SSE主题订阅监听器成功");
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
