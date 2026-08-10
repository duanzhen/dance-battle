package org.dromara.common.sse.controller;

import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.sse.core.SseEmitterManager;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.dto.SceneSwitchDto;
import org.dromara.common.sse.dto.TournamentSseMessageDto;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

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
                                     @RequestParam("tournamentId") String tournamentId) {
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
    public R<Void> switchScene(@RequestBody SceneSwitchDto dto) {
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
     * 清理资源。此方法目前不执行任何操作，但避免因未实现而导致错误
     */
    @Override
    public void destroy() throws Exception {
        // 销毁时不需要做什么 此方法避免无用操作报错
    }

}
