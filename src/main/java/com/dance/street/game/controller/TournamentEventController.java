package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.service.ITRefereeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 赛事事件 SSE 长连接:赛段/场次/打分等事件变化实时推送。
 * 裁判端复用同一通道:带 authKey 时按裁判身份连接,只收命中自己的定向事件。
 *
 * @author duane
 * @date 2026-01-06
 */
@SaIgnore
@RequiredArgsConstructor
@RestController
public class TournamentEventController {

    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;
    private final ITRefereeService refereeService;

    @GetMapping(value = "/tournament/event/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter event(@RequestParam("tournamentId") Long tournamentId,
                            @RequestParam(value = "authKey", required = false) String authKey) {
        if (StringUtils.isNotBlank(authKey)) {
            TRefereeVo referee = refereeService.findByAuthKey(authKey);
            if (referee == null) {
                throw new ServiceException("裁判认证失败:authKey 无效");
            }
            if (referee.getTournamentId() != null && !referee.getTournamentId().equals(tournamentId)) {
                throw new ServiceException("裁判不属于该赛事");
            }
            return tournamentEventSseEmitterManager.connectReferee(tournamentId, referee.getId());
        }
        return tournamentEventSseEmitterManager.connect(tournamentId);
    }
}
