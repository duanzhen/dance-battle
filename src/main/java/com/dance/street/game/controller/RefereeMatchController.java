package com.dance.street.game.controller;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeParticipantInfo;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.interceptor.RefereeAuthInterceptor;
import com.dance.street.game.service.IRefereeMatchService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 裁判端接口:authKey 即认证凭证,由 RefereeAuthInterceptor 校验。
 *
 * <p>控制器只负责鉴权、取凭证与转发;判罚页要的数据组装与提交校验都在
 * {@link IRefereeMatchService} 里,不在这里查库。</p>
 *
 * @author duane
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/referee-match")
public class RefereeMatchController {

    private final HttpServletRequest request;
    private final IRefereeMatchService refereeMatchService;
    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;

    /**
     * 裁判端 SSE 长连接:赛段/场次/打分变化时实时推送刷新
     */
    @GetMapping("/sse")
    public SseEmitter sse() {
        TRefereeVo referee = currentReferee();
        return tournamentEventSseEmitterManager.connectReferee(referee.getTournamentId(), referee.getId());
    }

    /**
     * 裁判端判罚页数据:自动定位当前赛段与场次,也可用 stageId / matchId 显式切换。
     */
    @GetMapping("/my-match")
    public R<RefereeMatchVo> getMyMatch(@RequestParam(required = false) Long stageId,
                                        @RequestParam(required = false) Long matchId) {
        TRefereeVo referee = currentReferee();
        return R.ok(refereeMatchService.myMatch(referee.getTournamentId(), referee.getId(),
            referee.getName(), stageId, matchId));
    }

    /**
     * 裁判提交打分
     */
    @PostMapping("/{matchId}/submit-score")
    public R<MatchResultVo> submitScore(@PathVariable Long matchId,
                                        @RequestBody SubmitResultBo bo) {
        TRefereeVo referee = currentReferee();
        return R.ok(refereeMatchService.submitScore(matchId, referee.getTournamentId(),
            referee.getId(), referee.getName(), bo));
    }

    /**
     * 轻量取当前场次各参赛方的累计分/名次:打分事件触发的局部刷新用,
     * 不再每次全量拉 my-match。
     */
    @GetMapping("/{matchId}/scores")
    public R<List<RefereeParticipantInfo>> matchScores(@PathVariable Long matchId) {
        TRefereeVo referee = currentReferee();
        return R.ok(refereeMatchService.matchScores(matchId, referee.getTournamentId(), referee.getId()));
    }

    /** 当前请求携带的裁判凭证(由 RefereeAuthInterceptor 校验后写入) */
    private TRefereeVo currentReferee() {
        return (TRefereeVo) request.getAttribute(RefereeAuthInterceptor.REFEREE_ATTR);
    }
}
