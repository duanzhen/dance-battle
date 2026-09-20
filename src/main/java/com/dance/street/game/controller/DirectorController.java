package com.dance.street.game.controller;

import com.dance.street.game.domain.bo.FreeMatchAdvanceBo;
import com.dance.street.game.domain.bo.FreeMatchBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
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
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import com.dance.street.game.service.impl.StageChain;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ITCompetitorService competitorService;
    private final ITStageLifecycleService stageLifecycleService;
    private final ITMatchResultService matchResultService;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    /** 赛段链遍历的唯一入口(以 next 链为事实源) */
    private final StageChain stageChain;

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
        boolean skipConfirm = stageLifecycleService.isAutoConfirmAdvancement(tournament.getId());
        for (TStageVo s : stages) {
            s.setSkipConfirm(skipConfirm);
        }
        enrichAwaitingAdvancement(stages);
        return R.ok(stages);
    }

    /**
     * 跳过中间态确认晋级(仅开启「跳过中间态确认阶段」配置时可用):
     * MC 导播台开始赛段时弹窗确认后调用,按当前预排把晋级者写入下一赛段
     */
    @Log(title = "导播台确认晋级", businessType = BusinessType.UPDATE)
    @PostMapping("/stage/{id}/advance")
    public R<Integer> advanceStage(@PathVariable("id") Long id, HttpServletRequest request) {
        TTournamentVo tournament = currentTournament(request);
        assertStageInTournament(tournament, id);
        if (!stageLifecycleService.isAutoConfirmAdvancement(tournament.getId())) {
            throw new ServiceException("未开启「跳过中间态确认阶段」配置,请先在管理端中间态确认晋级");
        }
        // 确认晋级作用于「要开始赛段的上一赛段」(已 SETTLED 的源赛段),把晋级者写入本赛段
        // 上一赛段由 next 链推导(prev 列仅展示字段)
        TStage prev = stageChain.prevOf(stageMapper.selectById(id));
        if (prev == null) {
            throw new ServiceException("该赛段没有上一赛段,无需确认晋级");
        }
        return R.ok(stageLifecycleService.calculateAdvancement(prev.getId()));
    }

    /**
     * 标记「等待中间态确认晋级」:与后端 startStage 的守卫一致——
     * 上一赛段已 SETTLED 且存在晋级者/同分待定,但本赛段尚未接收带来源参赛方。
     * 导播端据此前置提示并在管理端确认前禁用「开始赛段」。
     */
    private void enrichAwaitingAdvancement(List<TStageVo> stages) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        // 批量口径:此前每个赛段 5 条 SQL(查赛段 + 查前驱 + 3 次 count),
        // 导播台首页每次刷新都跑一遍,16 段赛事就是 80 条。现在整页共 3 条。
        Set<Long> tournamentIds = stages.stream()
            .map(TStageVo::getTournamentId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (tournamentIds.isEmpty()) {
            return;
        }
        List<TStage> allStages = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getTournamentId, tournamentIds));
        Map<Long, TStage> stageById = allStages.stream()
            .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        // 前驱按 next 链一次性推导(不再逐段 prevOf)
        Map<Long, Long> prevByStage = new HashMap<>();
        for (Long tid : tournamentIds) {
            prevByStage.putAll(stageChain.prevIdsFromChain(tid));
        }
        // 参赛方统计:一次取回相关赛段的行,在内存里数
        Set<Long> involved = new HashSet<>();
        for (TStageVo s : stages) {
            if (s.getId() != null) {
                involved.add(s.getId());
            }
            Long prevId = prevByStage.get(s.getId());
            if (prevId != null) {
                involved.add(prevId);
            }
        }
        // stageId -> [已接收晋级者数, 晋级数, 待定数]
        Map<Long, long[]> stats = new HashMap<>();
        if (!involved.isEmpty()) {
            List<TCompetitor> rows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .in(TCompetitor::getStageId, involved)
                .select(TCompetitor::getStageId, TCompetitor::getOutcomeStatus,
                    TCompetitor::getSourceCompetitorId));
            for (TCompetitor c : rows) {
                long[] st = stats.computeIfAbsent(c.getStageId(), k -> new long[3]);
                if (c.getSourceCompetitorId() != null) {
                    st[0]++;
                }
                if (OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())) {
                    st[1]++;
                } else if (OutcomeStatusEnum.PENDING.getCode().equals(c.getOutcomeStatus())) {
                    st[2]++;
                }
            }
        }
        for (TStageVo stage : stages) {
            Long prevId = prevByStage.get(stage.getId());
            // prev 列只是展示字段:这里统一按 next 链推导覆盖,
            // 避免列与链不同步时导播台把「上一赛段」显示成「未知」
            stage.setPrevStageId(prevId);
            TStage prev = prevId == null ? null : stageById.get(prevId);
            if (prev == null || !StageConstants.STAGE_SETTLED.equals(prev.getStatus())) {
                continue;
            }
            if (stats.getOrDefault(stage.getId(), new long[3])[0] > 0) {
                continue;
            }
            long[] src = stats.getOrDefault(prevId, new long[3]);
            stage.setAwaitingAdvancement(src[1] > 0 || src[2] > 0);
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
     * 开始赛段:DRAFT→GAMING
     */
    @Log(title = "导播台开始赛段", businessType = BusinessType.UPDATE)
    @PutMapping("/stage/{id}/start")
    public R<Void> startStage(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        stageLifecycleService.startStage(id);
        return R.ok();
    }

    /**
     * 完成赛段:GAMING→SETTLED。
     *
     * <p>不能结束时返回 {@code completed=false} + {@code message}(如海选产生二海),
     * 不算错误;导播台据此前置提示并保持赛段进行中。</p>
     */
    @Log(title = "导播台完成赛段", businessType = BusinessType.UPDATE)
    @PutMapping("/stage/{id}/complete")
    public R<StageCompleteVo> completeStage(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        return R.ok(stageLifecycleService.completeStage(id));
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
     * 自由对抗:本赛段参赛选手(供导播台选人对战 / 勾选晋级)
     */
    @GetMapping("/stage/{id}/competitors")
    public R<List<TCompetitorVo>> stageCompetitors(@PathVariable("id") Long id, HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        TCompetitorBo bo = new TCompetitorBo();
        bo.setStageId(id);
        return R.ok(competitorService.queryList(bo));
    }

    /**
     * 自由对抗:手动添加一场对战(线下抽签/指认确定的两名选手),返回新建场次ID
     */
    @Log(title = "导播台自由对添加对战", businessType = BusinessType.INSERT)
    @PostMapping("/stage/{id}/free-match")
    public R<Long> createFreeMatch(@PathVariable("id") Long id,
                                   @Validated @RequestBody FreeMatchBo bo,
                                   HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        return R.ok(stageLifecycleService.createFreeMatch(id, bo.getCompetitorAId(), bo.getCompetitorBId()));
    }

    /**
     * 自由对抗:删除一场对战(误加时使用,已结算场次需先重置)
     */
    @Log(title = "导播台自由对删除对战", businessType = BusinessType.DELETE)
    @DeleteMapping("/match/{id}/free-match")
    public R<Void> deleteFreeMatch(@PathVariable("id") Long id, HttpServletRequest request) {
        assertMatchInTournament(currentTournament(request), id);
        stageLifecycleService.deleteFreeMatch(id);
        return R.ok();
    }

    /**
     * 自由对抗:手动选择晋级者(任意人数),未选中的标记淘汰
     */
    @Log(title = "导播台自由对选择晋级", businessType = BusinessType.UPDATE)
    @PutMapping("/stage/{id}/free-match-advancers")
    public R<Integer> selectFreeMatchAdvancers(@PathVariable("id") Long id,
                                               @RequestBody FreeMatchAdvanceBo bo,
                                               HttpServletRequest request) {
        assertStageInTournament(currentTournament(request), id);
        return R.ok(stageLifecycleService.selectFreeMatchAdvancers(id, bo.getCompetitorIds()));
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
