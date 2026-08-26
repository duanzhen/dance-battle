package com.dance.street.game.service;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.dromara.common.sse.dto.TournamentEventSseMessageDto;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.mapper.TStageMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 裁判端 SSE 推送通知器。
 * 赛段/场次/打分发生变更时调用,按赛段分配的裁判定向推送,前端收到后刷新最新状态。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RefereeSseNotifier {

    private final ITRefereeStageService refereeStageService;
    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;
    private final TStageMapper stageMapper;

    /** 赛段级变更(开始/完成/晋级) */
    public void notifyStage(Long stageId, String type) {
        notify(stageId, null, type);
    }

    /** 场次级变更(打分/结算/重置) */
    public void notifyMatch(Long stageId, Long matchId, String type) {
        notify(stageId, matchId, type);
    }

    private void notify(Long stageId, Long matchId, String type) {
        try {
            if (stageId == null) {
                return;
            }
            List<Long> refereeIds = refereeStageService.getRefereeIdsByStageId(stageId);
            if (CollUtil.isEmpty(refereeIds)) {
                return;
            }
            TStage stage = stageMapper.selectById(stageId);
            if (stage == null || stage.getTournamentId() == null) {
                return;
            }
            // 注意:雪花ID超 JS 安全整数,必须序列化为字符串,否则前端 JSON.parse 后精度丢失
            StringBuilder sb = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"tournamentId\":\"").append(stage.getTournamentId()).append('"')
                .append(",\"stageId\":\"").append(stageId).append('"');
            if (matchId != null) {
                sb.append(",\"matchId\":\"").append(matchId).append('"');
            }
            sb.append("}");
            TournamentEventSseMessageDto dto = new TournamentEventSseMessageDto();
            dto.setTournamentId(stage.getTournamentId());
            dto.setRefereeIds(refereeIds);
            dto.setMessage(sb.toString());
            tournamentEventSseEmitterManager.publishMessage(dto);
        } catch (Exception e) {
            // SSE 推送失败不影响业务主流程
            log.warn("裁判SSE推送失败 stageId={} matchId={} type={}: {}", stageId, matchId, type, e.getMessage());
        }
    }
}
