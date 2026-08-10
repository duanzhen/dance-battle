package com.dance.street.game.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.dromara.common.sse.dto.TournamentEventSseMessageDto;
import org.springframework.stereotype.Component;

/**
 * 赛事事件 SSE 通知器。
 * 赛段/场次/打分等赛事事件变化时按 tournamentId 广播,大屏组件订阅后刷新,替代轮询。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class TournamentEventNotifier {

    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;

    public void notify(Long tournamentId, Long stageId, Long matchId, String type) {
        try {
            if (tournamentId == null) {
                return;
            }
            // 注意:雪花ID超 JS 安全整数,必须序列化为字符串,否则前端 JSON.parse 后精度丢失
            StringBuilder sb = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"tournamentId\":\"").append(tournamentId).append('"');
            if (stageId != null) {
                sb.append(",\"stageId\":\"").append(stageId).append('"');
            }
            if (matchId != null) {
                sb.append(",\"matchId\":\"").append(matchId).append('"');
            }
            sb.append("}");
            TournamentEventSseMessageDto dto = new TournamentEventSseMessageDto();
            dto.setTournamentId(tournamentId);
            dto.setMessage(sb.toString());
            tournamentEventSseEmitterManager.publishMessage(dto);
        } catch (Exception e) {
            // SSE 推送失败不影响业务主流程
            log.warn("赛事事件SSE推送失败 tournamentId={} stageId={} matchId={} type={}: {}",
                tournamentId, stageId, matchId, type, e.getMessage());
        }
    }
}
