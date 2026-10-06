package com.dance.street.game.controller;

import com.dance.street.game.interceptor.DirectorAuthInterceptor;
import com.dance.street.game.service.ITVisScreenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.SceneSwitchDto;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 大屏投射入口(导播凭证鉴权,路径与原 SSE 控制器一致)。
 *
 * <p>投射不再只是「写 Redis + 广播」:同时把当前场景持久化到 {@code t_vis_screen.current_scene_id},
 * 这样多台控制端共用同一份屏幕列表时,新打开的控制端也能读到每块屏当前投射的场景。</p>
 *
 * @author duane
 */
@ConditionalOnProperty(value = "sse.enabled", havingValue = "true")
@RequiredArgsConstructor
@RestController
public class ScreenProjectionController {

    private final TournamentSseEmitterManager tournamentSseEmitterManager;
    private final ITVisScreenService visScreenService;

    /**
     * 投射场景到屏幕:写当前场景映射(Redis)+ 持久化到屏幕表 + 广播给该屏幕所有终端。
     */
    @PostMapping(value = "/tournament/screen/scene")
    public R<Void> switchScene(@RequestBody SceneSwitchDto dto, HttpServletRequest request) {
        DirectorAuthInterceptor.requireTournament(request, dto.getTournamentId());

        // Redis 映射:大屏浏览端(重)连接时后端据此补发当前场景
        tournamentSseEmitterManager.setCurrentScene(dto.getScreenId(), dto.getSceneId());
        // 数据库:多控制端共用屏幕列表时,当前投射也一并共享
        visScreenService.bindCurrentScene(parseId(dto.getScreenId()), parseId(dto.getSceneId()));

        String sceneIdJson = dto.getSceneId() != null ? "\"" + dto.getSceneId() + "\"" : "null";
        String message = String.format("{\"screenId\":\"%s\",\"sceneId\":%s,\"type\":\"sceneSwitch\"}",
            dto.getScreenId(), sceneIdJson);
        publishToScreen(dto.getScreenId(), message);
        return R.ok();
    }

    /**
     * 清除屏幕投射:与投射同一鉴权,sceneId 置空即删除映射并广播。
     */
    @DeleteMapping(value = "/tournament/screen/scene")
    public R<Void> clearScene(@RequestParam("screenId") String screenId,
                              @RequestParam("tournamentId") String tournamentId,
                              HttpServletRequest request) {
        DirectorAuthInterceptor.requireTournament(request, tournamentId);

        tournamentSseEmitterManager.setCurrentScene(screenId, null);
        visScreenService.bindCurrentScene(parseId(screenId), null);

        String message = String.format("{\"screenId\":\"%s\",\"sceneId\":null,\"type\":\"sceneSwitch\"}", screenId);
        publishToScreen(screenId, message);
        return R.ok();
    }

    private void publishToScreen(String screenId, String message) {
        TournamentSseMessageDto sseMessage = new TournamentSseMessageDto();
        sseMessage.setMessage(message);
        sseMessage.setScreenIds(Collections.singletonList(screenId));
        tournamentSseEmitterManager.publishMessage(sseMessage);
    }

    private static Long parseId(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // 历史遗留的本地 UUID 屏幕:不落库,仅走 Redis + 广播
            return null;
        }
    }

}
