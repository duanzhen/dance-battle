package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.bo.TRefereeBo;
import com.dance.street.game.domain.bo.TVisWidgetBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.MatchRefereeVo;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TMatchParticipantVo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.vo.TVisWidgetVo;
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITMatchParticipantService;
import com.dance.street.game.service.ITMatchRefereeService;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITPlayerService;
import com.dance.street.game.service.ITRefereeService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITTournamentService;
import com.dance.street.game.service.ITVisSceneService;
import com.dance.street.game.service.ITVisWidgetService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大屏(投射页)公开数据接口。
 *
 * <p>投射端({@code /tournament/projection?screenId=...})是现场/线上公开播放的大屏,
 * 没有、也不该有管理员登录态,更不会携带任何凭证。此前大屏复用了管理端 {@code /game/**}
 * 的读取接口({@code @SaCheckPermission}),在没有管理员 JWT 的机器上全部 401,而管理端
 * 响应拦截器见到 401 就弹「登录状态已过期」,把大屏整个挡住。</p>
 *
 * <p>这里把这批"本来就是给观众看"的数据单独收拢成一组 {@code @SaIgnore} 的只读接口:
 * 全部为查询、无写入,返回结构与对应的管理端接口一致(直接委托同一批 service),
 * 前端大屏只依赖本控制器,和鉴权体系彻底解耦。赛事凭证(authKey)不在任何返回的 VO 里,
 * 因此开放这批只读数据不会泄露入台凭证。</p>
 *
 * @author duane
 */
@SaIgnore
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/tournament/screen")
public class ScreenController {

    private final ITVisSceneService visSceneService;
    private final ITVisWidgetService visWidgetService;
    private final ITTournamentService tournamentService;
    private final ITStageService stageService;
    private final ITStageLifecycleService stageLifecycleService;
    private final ITMatchService matchService;
    private final ITMatchParticipantService matchParticipantService;
    private final ITCompetitorService competitorService;
    private final ITPlayerService playerService;
    private final ITRefereeService refereeService;
    private final ITMatchRefereeService matchRefereeService;
    private final ITRefereeStageService refereeStageService;

    // ==================== 场景 / 控件 ====================

    /** 场景详情:大屏按 screenId 拿到当前投射的场景后读取 */
    @GetMapping("/scene/{id}")
    public R<TVisSceneVo> scene(@PathVariable("id") Long id) {
        return R.ok(visSceneService.queryById(id));
    }

    /** 场景下的控件列表 */
    @GetMapping("/widget/list")
    public TableDataInfo<TVisWidgetVo> widgets(TVisWidgetBo bo, PageQuery pageQuery) {
        return visWidgetService.queryPageList(bo, pageQuery);
    }

    // ==================== 赛事 / 赛段 ====================

    /** 赛事信息(名称、主题配色等;不含入台凭证) */
    @GetMapping("/tournament/{id}")
    public R<TTournamentVo> tournament(@PathVariable("id") Long id) {
        return R.ok(tournamentService.queryById(id));
    }

    /** 赛段详情 */
    @GetMapping("/stage/{id}")
    public R<TStageVo> stage(@PathVariable("id") Long id) {
        return R.ok(stageService.queryById(id));
    }

    /** 赛前对阵(对战树控件) */
    @GetMapping("/stage/{id}/prebracket")
    public R<PreBracketVo> prebracket(@PathVariable("id") Long stageId) {
        return R.ok(stageService.getPreBracket(stageId));
    }

    /** 排名赛明细 */
    @GetMapping("/stage/{id}/rank-detail")
    public R<RankDetailVo> rankDetail(@PathVariable("id") Long stageId) {
        return R.ok(stageLifecycleService.getRankDetail(stageId));
    }

    /** 海选结果(原始成绩 + 二海/三海加赛明细) */
    @GetMapping("/stage/{id}/audition-result")
    public R<AuditionResultVo> auditionResult(@PathVariable("id") Long stageId) {
        return R.ok(stageLifecycleService.queryAuditionResult(stageId));
    }

    /** 擂台赛总览:轮转队列与当前对决 */
    @GetMapping("/stage/{id}/arena-overview")
    public R<ArenaOverviewVo> arenaOverview(@PathVariable("id") Long stageId) {
        return R.ok(stageLifecycleService.getArenaOverview(stageId));
    }

    /** 全赛事赛段流程(当前场次控件) */
    @GetMapping("/flow/{tournamentId}")
    public R<StageFlowVo> flow(@PathVariable("tournamentId") Long tournamentId) {
        return R.ok(stageService.getFlowByTournamentId(tournamentId));
    }

    // ==================== 场次 / 参赛方 ====================

    /** 场次列表(按赛段) */
    @GetMapping("/match/list")
    public TableDataInfo<TMatchVo> matches(TMatchBo bo, PageQuery pageQuery) {
        return matchService.queryPageList(bo, pageQuery);
    }

    /** 场次详情 */
    @GetMapping("/match/{id}")
    public R<TMatchVo> match(@PathVariable("id") Long id) {
        return R.ok(matchService.queryById(id));
    }

    /** 场次当前上场选手(海选大屏标记) */
    @GetMapping("/match/{id}/current-competitor")
    public R<Long> currentCompetitor(@PathVariable("id") Long id) {
        return R.ok(stageLifecycleService.getMatchCurrentCompetitor(id));
    }

    /** 场次参赛明细列表(按场次/赛段) */
    @GetMapping("/matchParticipant/list")
    public TableDataInfo<TMatchParticipantVo> matchParticipants(TMatchParticipantBo bo, PageQuery pageQuery) {
        return matchParticipantService.queryPageList(bo, pageQuery);
    }

    /** 参赛单位列表(按赛段/赛事) */
    @GetMapping("/competitor/list")
    public TableDataInfo<TCompetitorVo> competitors(TCompetitorBo bo, PageQuery pageQuery) {
        return competitorService.queryPageList(bo, pageQuery);
    }

    /** 选手自然人列表(头像等) */
    @GetMapping("/player/list")
    public TableDataInfo<TPlayerVo> players(TPlayerBo bo, PageQuery pageQuery) {
        return playerService.queryPageList(bo, pageQuery);
    }

    // ==================== 裁判展示 ====================

    /** 裁判列表(按赛事) */
    @GetMapping("/referee/list")
    public TableDataInfo<TRefereeVo> referees(TRefereeBo bo, PageQuery pageQuery) {
        return refereeService.queryPageList(bo, pageQuery);
    }

    /** 某赛段各场次(圈)已分配的裁判 */
    @GetMapping("/match-referee/list")
    public R<List<MatchRefereeVo>> matchReferees(@RequestParam Long stageId) {
        return R.ok(matchRefereeService.listByStageId(stageId));
    }

    /** 某赛段已分配的裁判ID */
    @GetMapping("/referee-stage/referee-ids")
    public R<List<Long>> stageRefereeIds(@RequestParam Long stageId) {
        return R.ok(refereeStageService.getRefereeIdsByStageId(stageId));
    }
}
