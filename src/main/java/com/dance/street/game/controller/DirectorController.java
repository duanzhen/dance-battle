package com.dance.street.game.controller;

import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.interceptor.DirectorAuthInterceptor;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;

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
        List<TStageVo> stages = stageService.queryList(bo);
        enrichAwaitingAdvancement(stages);
        return R.ok(stages);
    }

    /**
     * 标记「等待中间态确认晋级」:与后端 startStage 的守卫一致——
     * 上一赛段已 SETTLED 且存在晋级者/同分待定,但本赛段尚未接收带来源参赛方。
     * 导播端据此前置提示并在管理端确认前禁用「开始赛段」。
     */
    private void enrichAwaitingAdvancement(List<TStageVo> stages) {
        if (stages == null) {
            return;
        }
        for (TStageVo stage : stages) {
            if (stage.getPrevStageId() == null) {
                continue;
            }
            TStage prev = stageMapper.selectById(stage.getPrevStageId());
            if (prev == null || !StageConstants.STAGE_SETTLED.equals(prev.getStatus())) {
                continue;
            }
            long confirmed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .isNotNull(TCompetitor::getSourceCompetitorId));
            if (confirmed > 0) {
                continue;
            }
            long srcAdvance = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, prev.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
            long srcPending = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, prev.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            stage.setAwaitingAdvancement(srcAdvance > 0 || srcPending > 0);
        }
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
    public R<java.util.Map<String, String>> completeStage(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        String status = stageLifecycleService.completeStage(id);
        // 返回结算后赛段真实状态:SETTLED=已完成;GAMING=海选产生二海等,赛段保持进行中
        return R.ok(java.util.Map.of("status", status));
    }

    /**
     * 导播台重置赛段为草稿(排错/重抽签用):清除已生成对阵,参赛方回退待定,可重新生成。
     */
    @Log(title = "导播台重置赛段草稿", businessType = BusinessType.UPDATE)
    @PostMapping("/stage/{id}/reset-to-draft")
    public R<Void> resetStageToDraft(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.resetStageToDraft(id);
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
     * 擂台赛临时弃权:该选手本轮跳过、排到队尾,后续仍参与排队与排名;
     * 进行中的对决包含该选手时作废,队列下一位顶上来
     */
    @Log(title = "导播台擂台临时弃权", businessType = BusinessType.UPDATE)
    @PostMapping("/stage/{id}/arena-temp-withdraw")
    public R<Void> arenaTempWithdraw(@PathVariable("id") Long id,
                                     @RequestParam Long competitorId,
                                     HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.tempWithdrawArenaCompetitor(id, competitorId);
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
     * 取消开始场次(误触回退):GAMING → PENDING,清空本场已提交分数/结果,
     * 用于导播台点错「开始」后还原为待开始。仅淘汰赛支持。
     */
    @Log(title = "导播台取消开始场次", businessType = BusinessType.UPDATE)
    @PostMapping("/match/{id}/cancel-start")
    public R<Void> cancelStartMatch(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        matchResultService.cancelStartMatch(id);
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

    /**
     * 标记场次当前上场选手(海选大屏):MC 点击选手名字后调用,仅标记并广播,不参与结算。
     * competitorId 传 null 清除标记。
     */
    @Log(title = "标记当前上场选手", businessType = BusinessType.UPDATE)
    @PutMapping("/match/{id}/current-competitor")
    public R<Void> setCurrentCompetitor(@PathVariable("id") Long id,
                                        @RequestBody java.util.Map<String, Object> body,
                                        HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        Object cid = body.get("competitorId");
        Long competitorId = cid == null ? null : Long.valueOf(cid.toString());
        stageLifecycleService.setMatchCurrentCompetitor(id, competitorId);
        return R.ok();
    }

    /** 查询场次当前标记的上场选手(导播台高亮用);未标记返回 null */
    @GetMapping("/match/{id}/current-competitor")
    public R<Long> getCurrentCompetitor(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        return R.ok(stageLifecycleService.getMatchCurrentCompetitor(id));
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
