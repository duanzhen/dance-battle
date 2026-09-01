package com.dance.street.game.controller;

import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.web.core.BaseController;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.excel.utils.ExcelUtil;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.CircleAssignVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.CalculateAdvancementBo;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.AddGuestBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.bo.PromoteReplacementBo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 赛段流程
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/stage")
public class TStageController extends BaseController {

    private final ITStageService tStageService;
    private final ITStageLifecycleService tStageLifecycleService;

    /**
     * 查询赛段流程列表
     */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/list")
    public TableDataInfo<TStageVo> list(TStageBo bo, PageQuery pageQuery) {
        return tStageService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出赛段流程列表
     */
    @SaCheckPermission("game:stage:export")
    @Log(title = "赛段流程", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TStageBo bo, HttpServletResponse response) {
        List<TStageVo> list = tStageService.queryList(bo);
        ExcelUtil.exportExcel(list, "赛段流程", TStageVo.class, response);
    }

    /**
     * 获取赛段流程详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/{id}")
    public R<TStageVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tStageService.queryById(id));
    }

    /**
     * 新增赛段流程
     */
    @SaCheckPermission("game:stage:add")
    @Log(title = "赛段流程", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TStageVo> add(@Validated(AddGroup.class) @RequestBody TStageBo bo) {
        return R.ok(tStageService.insertByBo(bo));
    }

    /**
     * 修改赛段流程
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "赛段流程", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TStageVo> edit(@Validated(EditGroup.class) @RequestBody TStageBo bo) {
        return R.ok(tStageService.updateByBo(bo));
    }

    /**
     * 删除赛段流程
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:stage:remove")
    @Log(title = "赛段流程", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tStageService.deleteWithValidByIds(List.of(ids), true));
    }

    /**
     * 根据比赛ID获取第一个赛段
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/first/{tournamentId}")
    public R<TStageVo> getFirstStage(@NotNull(message = "比赛ID不能为空")
                                     @PathVariable Long tournamentId) {
        return R.ok(tStageService.getFirstStageByTournamentId(tournamentId));
    }

    /**
     * 大屏赛程流转:赛事全部赛段链 + 当前进行中赛段/场次
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/flow/{tournamentId}")
    public R<StageFlowVo> flow(@NotNull(message = "比赛ID不能为空")
                               @PathVariable Long tournamentId) {
        return R.ok(tStageService.getFlowByTournamentId(tournamentId));
    }

    /**
     * 下一赛段对战树预排:上一赛段胜者(含未最终确认)按种子顺位排入本赛段
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/prebracket/{stageId}")
    public R<PreBracketVo> prebracket(@NotNull(message = "赛段ID不能为空")
                                      @PathVariable Long stageId) {
        return R.ok(tStageService.getPreBracket(stageId));
    }

    // ==================== 赛段生命周期(赛事流程引擎)====================

    /**
     * 初始化赛段:锁定参赛方名单并排种子顺位,DRAFT→PENDING
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "赛段初始化", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/initialize")
    public R<Void> initialize(@Validated @RequestBody InitializeStageBo bo) {
        tStageLifecycleService.initialize(bo);
        return R.ok();
    }

    /**
     * 生成对阵:按赛制生成比赛场次 + 对阵连线
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "生成对阵", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/generate-matches")
    public R<Void> generateMatches(@Validated @RequestBody GenerateMatchesBo bo) {
        tStageLifecycleService.generateMatches(bo);
        return R.ok();
    }

    /**
     * 海选分圈随机抽取:把已签到选手随机均衡分配到各圈场次(可重抽,赛段未开始时)
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "随机抽取圈", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/random-circles")
    public R<List<CircleAssignVo>> randomCircles(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        return R.ok(tStageLifecycleService.randomCircles(id));
    }

    /**
     * 擂台赛参赛选手弃权:弃权后不再参与排队;进行中的对决包含该选手时作废该对决,队列下一位补位
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "擂台赛选手弃权", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/competitor/{competitorId}/withdraw")
    public R<Void> withdrawCompetitor(@NotNull(message = "赛段ID不能为空") @PathVariable Long id,
                                      @NotNull(message = "参赛选手ID不能为空") @PathVariable Long competitorId) {
        tStageLifecycleService.withdrawArenaCompetitor(id, competitorId);
        return R.ok();
    }

    /**
     * 开始赛段:PENDING→GAMING
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "开始赛段", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{id}/start")
    public R<Void> start(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        tStageLifecycleService.startStage(id);
        return R.ok();
    }

    /**
     * 完成赛段:GAMING→SETTLED(需所有场次已结算)
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "完成赛段", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{id}/complete")
    public R<java.util.Map<String, String>> complete(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        String status = tStageLifecycleService.completeStage(id);
        // 返回结算后赛段真实状态:SETTLED=已完成;GAMING=海选产生二海等,赛段保持进行中
        return R.ok(java.util.Map.of("status", status));
    }

    /**
     * 重置赛段为草稿:清除已生成对阵(级联轮次/参赛明细/打分),参赛方回退待定,
     * isInitialized 归零,可重新排种子/生成对阵。仅 DRAFT/PENDING 状态可用。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "重置赛段草稿", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/reset-to-draft")
    public R<Void> resetToDraft(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        tStageLifecycleService.resetStageToDraft(id);
        return R.ok();
    }

    /**
     * 擂台赛:创建并开始下一场对决(胜者守擂、败者排到队尾、平局双方均排到队尾)。赛段须 GAMING 且无进行中对决。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "擂台赛下一场", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/arena-next")
    public R<Void> arenaNext(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        tStageLifecycleService.startNextArenaMatch(id);
        return R.ok();
    }

    /**
     * 擂台赛总览:轮转队列(含每人积分)与当前对决
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/{id}/arena-overview")
    public R<ArenaOverviewVo> arenaOverview(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        return R.ok(tStageLifecycleService.getArenaOverview(id));
    }

    /**
     * 计算晋级(MANUAL 模式显式调用):把本赛段晋级者推进下一赛段。返回晋级人数。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "计算晋级", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/calculate-advancement")
    public R<Integer> calculateAdvancement(@NotNull(message = "赛段ID不能为空") @PathVariable Long id,
                                           @RequestBody(required = false) CalculateAdvancementBo bo) {
        if (bo == null) {
            bo = new CalculateAdvancementBo();
        }
        bo.setStageId(id);
        return R.ok(tStageLifecycleService.calculateAdvancement(bo));
    }

    /**
     * 导出海选结果:号码 / 选手名 / 各裁判分数(每裁判一列) / 总平均分 / 排名
     */
    @SaCheckPermission("game:stage:query")
    @Log(title = "导出海选结果", businessType = BusinessType.EXPORT)
    @GetMapping("/{id}/export-audition-result")
    public void exportAuditionResult(@NotNull(message = "赛段ID不能为空") @PathVariable Long id,
                                     HttpServletResponse response) {
        tStageLifecycleService.exportAuditionResult(id, response);
    }

    /**
     * 查询海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
     * 二海分数只用于同分者决出晋级顺序,不计入原始总分;各组件统一消费本结果。
     */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/{id}/audition-result")
    public R<AuditionResultVo> auditionResult(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        return R.ok(tStageLifecycleService.queryAuditionResult(id));
    }

    /**
     * GUEST 加入:除海选外任意赛段,在赛段规划/未开始态(DRAFT/PENDING)且未初始化时加入;
     * 仅创建参赛单位进入 GUEST 池,不自动挂入任何场次;由导播按外部抽签结果设定种子顺序后,
     * initialize → generateMatches 生成对阵(GUEST 胜出即占晋级名额)。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "赛段 GUEST", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/{stageId}/guest")
    public R<TCompetitorVo> addGuest(@NotNull(message = "赛段ID不能为空") @PathVariable Long stageId,
                                     @Validated @RequestBody AddGuestBo bo) {
        bo.setStageId(stageId);
        return R.ok(tStageLifecycleService.addGuest(bo));
    }

    /**
     * 按外部抽签结果批量设定赛段参赛方种子顺序(seedRank 1..n,仅未初始化时允许)
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "设定种子顺序", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{stageId}/seed-order")
    public R<Integer> setSeedOrder(@NotNull(message = "赛段ID不能为空") @PathVariable Long stageId,
                                   @Validated @RequestBody SeedOrderBo bo) {
        bo.setStageId(stageId);
        return R.ok(tStageLifecycleService.setSeedOrder(bo));
    }

    /**
     * 排名赛同分晋级调整:赛段已结算(SETTLED)后,导播台在中间态手动指定
     * 晋级线上待定(同分并列)参赛者中的晋级者(传入全部即全部晋级,未选中的待定者淘汰)。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "同分晋级调整", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{stageId}/adjust-advancement")
    public R<Integer> adjustAdvancement(@NotNull(message = "赛段ID不能为空") @PathVariable Long stageId,
                                        @RequestBody List<Long> competitorIds) {
        return R.ok(tStageLifecycleService.adjustAdvancement(stageId, competitorIds));
    }

    /**
     * 海选弃权/顶替(结算后、确认晋级前):
     * 仅传 withdrawnCompetitorId = 标记弃权,其后晋级者名次整体前移(不顶替时末尾空位即轮空);
     * 传 replacementCompetitorId = 把任意被淘汰的选手顶替晋级,补齐到晋级名单末尾
     * (支持任意 被淘汰者→任意晋级者 的替换)。
     */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "海选弃权顶替", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{stageId}/promote-replacement")
    public R<Integer> promoteReplacement(@NotNull(message = "赛段ID不能为空") @PathVariable Long stageId,
                                         @RequestBody(required = false) PromoteReplacementBo bo) {
        if (bo == null) {
            bo = new PromoteReplacementBo();
        }
        return R.ok(tStageLifecycleService.promoteReplacement(
            stageId, bo.getWithdrawnCompetitorId(), bo.getReplacementCompetitorId()));
    }

    /**
     * 排名赛排名明细:各圈参赛者的总分与各维度聚合分(供排名展示组件维度模式使用)
     */
    @SaCheckPermission("game:stage:query")
    @GetMapping("/{id}/rank-detail")
    public R<RankDetailVo> rankDetail(@NotNull(message = "赛段ID不能为空") @PathVariable Long id) {
        return R.ok(tStageLifecycleService.getRankDetail(id));
    }
}
