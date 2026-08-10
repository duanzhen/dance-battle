package com.dance.street.game.controller;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.interceptor.DirectorAuthInterceptor;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 手机导播台接口：全部走赛事 auth_key 鉴权(DirectorAuthInterceptor),与管理员接口隔离。
 * 所有操作都校验资源归属当前赛事,防止越权操作其他赛事。
 *
 * @author duane
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/director")
public class DirectorController {

    private final ITTournamentService tournamentService;
    private final ITStageService stageService;
    private final ITMatchService matchService;
    private final ITStageLifecycleService stageLifecycleService;
    private final ITMatchResultService matchResultService;

    /**
     * 获取当前认证赛事信息(名称等)
     */
    @GetMapping("/tournament")
    public R<TTournamentVo> tournament(HttpServletRequest request) {
        return R.ok(currentTournament(request));
    }

    /**
     * 赛段列表(仅当前赛事)
     */
    @GetMapping("/stage/list")
    public R<List<TStageVo>> stageList(@RequestParam("tournamentId") Long tournamentId,
                                       HttpServletRequest request) {
        TTournamentVo tournament = currentTournament(request);
        if (!Objects.equals(tournament.getId(), tournamentId)) {
            throw new ServiceException("赛事凭证与请求不匹配");
        }
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        return R.ok(stageService.queryList(bo));
    }

    /**
     * 场次列表(按赛段,校验赛段属于当前赛事)
     */
    @GetMapping("/match/list")
    public R<List<TMatchVo>> matchList(@RequestParam("stageId") Long stageId,
                                       HttpServletRequest request) {
        TTournamentVo tournament = currentTournament(request);
        assertStageInTournament(tournament, stageId);
        TMatchBo bo = new TMatchBo();
        bo.setStageId(stageId);
        return R.ok(matchService.queryList(bo));
    }

    /**
     * 开始赛段:PENDING→GAMING
     */
    @Log(title = "导播台开始赛段", businessType = BusinessType.UPDATE)
    @PutMapping("/stage/{id}/start")
    public R<Void> startStage(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.startStage(id);
        return R.ok();
    }

    /**
     * 完成赛段:GAMING→SETTLED
     */
    @Log(title = "导播台完成赛段", businessType = BusinessType.UPDATE)
    @PutMapping("/stage/{id}/complete")
    public R<Void> completeStage(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.completeStage(id);
        return R.ok();
    }

    /**
     * 擂台赛:创建并开始下一场对决
     */
    @Log(title = "导播台擂台下一场", businessType = BusinessType.UPDATE)
    @PostMapping("/stage/{id}/arena-next")
    public R<Void> arenaNext(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.startNextArenaMatch(id);
        return R.ok();
    }

    /**
     * 开始指定场次
     */
    @Log(title = "导播台开始场次", businessType = BusinessType.UPDATE)
    @PostMapping("/match/{id}/start")
    public R<Void> startMatch(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        matchResultService.startMatch(id);
        return R.ok();
    }

    /**
     * 回退单场结算(调试用)
     */
    @Log(title = "导播台重置场次", businessType = BusinessType.UPDATE)
    @PostMapping("/match/{id}/reset")
    public R<Void> resetMatch(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        matchResultService.resetMatch(id);
        return R.ok();
    }

    /**
     * 提交比赛结果
     */
    @Log(title = "导播台提交结果", businessType = BusinessType.UPDATE)
    @PostMapping("/match/{id}/submit-result")
    public R<MatchResultVo> submitResult(@PathVariable("id") Long id,
                                         @RequestBody SubmitResultBo bo,
                                         HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        bo.setMatchId(id);
        return R.ok(matchResultService.submitResult(bo));
    }

    /**
     * 导播台确认公布结果(MANUAL 模式)
     */
    @Log(title = "导播台公布结果", businessType = BusinessType.UPDATE)
    @PostMapping("/match/{id}/publish-result")
    public R<MatchResultVo> publishResult(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        return R.ok(matchResultService.publishResult(id));
    }

    private TTournamentVo currentTournament(HttpServletRequest request) {
        return (TTournamentVo) request.getAttribute(DirectorAuthInterceptor.DIRECTOR_ATTR);
    }

    private void assertStageInTournament(TTournamentVo tournament, Long stageId) {
        TStageVo stage = stageService.queryById(stageId);
        if (stage == null || !Objects.equals(stage.getTournamentId(), tournament.getId())) {
            throw new ServiceException("赛段不存在或不属于当前赛事");
        }
    }

    private void assertMatchInTournament(TTournamentVo tournament, Long matchId) {
        TMatchVo match = matchService.queryById(matchId);
        if (match == null || !Objects.equals(match.getTournamentId(), tournament.getId())) {
            throw new ServiceException("场次不存在或不属于当前赛事");
        }
    }
}
