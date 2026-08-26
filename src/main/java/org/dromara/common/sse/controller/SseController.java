package org.dromara.common.sse.controller;

import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.sse.core.SseEmitterManager;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.SceneSwitchDto;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.interceptor.DirectorAuthInterceptor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * SSE 控制器
 *
 * @author Lion Li
 */
@RestController
@ConditionalOnProperty(value = "sse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SseController implements DisposableBean {

    private final SseEmitterManager sseEmitterManager;
    private final TournamentSseEmitterManager tournamentSseEmitterManager;

    /**
     * 建立 SSE 连接
     */
    @GetMapping(value = "${sse.path}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        String tokenValue = StpUtil.getTokenValue();
        Long userId = LoginHelper.getUserId();
        return sseEmitterManager.connect(userId, tokenValue);
    }

    /**
     * 关闭 SSE 连接
     */
    @SaIgnore
    @GetMapping(value = "${sse.path}/close")
    public R<Void> close() {
        String tokenValue = StpUtil.getTokenValue();
        Long userId = LoginHelper.getUserId();
        sseEmitterManager.disconnect(userId, tokenValue);
        return R.ok();
    }

    @SaIgnore
    @GetMapping(value = "/tournament/screen/view", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter screenView(@RequestParam("screenId") String screenId,
                                  @RequestParam("terminalId") String terminalId) {
        return tournamentSseEmitterManager.connect(screenId, terminalId,
            TournamentSseEmitterManager.ClientType.VIEWER);
    }

    @SaIgnore
    @GetMapping(value = "/tournament/screen/control", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter screenControl(@RequestParam(value = "screenIds", required = false) String screenIds,
                                    @RequestParam(value = "screenId", required = false) String screenId,
                                     @RequestParam("terminalId") String terminalId,
                                     @RequestParam("tournamentId") String tournamentId,
                                     HttpServletRequest request) {
        // 导播专用凭证鉴权:DirectorAuthInterceptor 已按 authKey 校验并注入赛事信息
        assertDirectorTournament(request, tournamentId);
        // 兼容单屏参数 screenId 与多屏参数 screenIds(逗号分隔)
        List<String> ids = StringUtils.isNotBlank(screenIds)
            ? Arrays.stream(screenIds.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList()
            : (StringUtils.isNotBlank(screenId) ? List.of(screenId) : List.of());
        if (ids.isEmpty()) {
            throw new ServiceException("screenIds 不能为空");
        }
        // 管理端连接时注册屏幕到指定赛事
        ids.forEach(s -> tournamentSseEmitterManager.registerScreen(s, tournamentId));
        return tournamentSseEmitterManager.connectManagers(ids, terminalId);
    }

    /**
     * 切换场景通知
     * 将场景ID通知到所有订阅了指定屏幕的终端（包括管理端和浏览端）
     *
     * @param dto 场景切换请求
     * @return 操作结果
     */
    @PostMapping(value = "/tournament/screen/scene")
    public R<Void> switchScene(@RequestBody SceneSwitchDto dto, HttpServletRequest request) {
        // 导播专用凭证鉴权:body 中的 tournamentId 必须与 authKey 所属赛事一致
        assertDirectorTournament(request, dto.getTournamentId());
        // 将场景ID存储到Redis（sceneId为null时删除映射）
        tournamentSseEmitterManager.setCurrentScene(dto.getScreenId(), dto.getSceneId());

        // 构建场景切换消息
        String sceneIdJson = dto.getSceneId() != null ? "\"" + dto.getSceneId() + "\"" : "null";
        String message = String.format("{\"screenId\":\"%s\",\"sceneId\":%s,\"type\":\"sceneSwitch\"}",
            dto.getScreenId(), sceneIdJson);

        // 通过Redis发布订阅向所有服务实例的指定屏幕发送消息
        TournamentSseMessageDto sseMessage = new TournamentSseMessageDto();
        sseMessage.setMessage(message);
        sseMessage.setScreenIds(java.util.Collections.singletonList(dto.getScreenId()));
        tournamentSseEmitterManager.publishMessage(sseMessage);

        return R.ok();
    }

    /**
     * 清除屏幕投射:与切换场景同一鉴权,sceneId 置空即删除映射并广播。
     */
    @DeleteMapping(value = "/tournament/screen/scene")
    public R<Void> clearScene(@RequestParam("screenId") String screenId,
                              @RequestParam("tournamentId") String tournamentId,
                              HttpServletRequest request) {
        assertDirectorTournament(request, tournamentId);
        tournamentSseEmitterManager.setCurrentScene(screenId, null);
        String message = String.format("{\"screenId\":\"%s\",\"sceneId\":null,\"type\":\"sceneSwitch\"}", screenId);
        TournamentSseMessageDto sseMessage = new TournamentSseMessageDto();
        sseMessage.setMessage(message);
        sseMessage.setScreenIds(java.util.Collections.singletonList(screenId));
        tournamentSseEmitterManager.publishMessage(sseMessage);
        return R.ok();
    }

    /**
     * 校验导播凭证所属赛事与请求目标赛事一致。
     * DirectorAuthInterceptor 已保证 authKey 有效并注入 DIRECTOR_ATTR。
     */
    private void assertDirectorTournament(HttpServletRequest request, String tournamentId) {
        TTournamentVo tournament = (TTournamentVo) request.getAttribute(DirectorAuthInterceptor.DIRECTOR_ATTR);
        if (tournament == null || tournament.getId() == null || StringUtils.isBlank(tournamentId)) {
            throw new ServiceException("赛事凭证与请求不匹配");
        }
        try {
            if (!Objects.equals(tournament.getId(), Long.valueOf(tournamentId.trim()))) {
                throw new ServiceException("赛事凭证与请求不匹配");
            }
        } catch (NumberFormatException e) {
            throw new ServiceException("赛事凭证与请求不匹配");
        }
    }

    /**
     * 清理资源。此方法目前不执行任何操作，但避免因未实现而导致错误
     */
    @Override
    public void destroy() throws Exception {
        // 销毁时不需要做什么 此方法避免无用操作报错
    }

}
