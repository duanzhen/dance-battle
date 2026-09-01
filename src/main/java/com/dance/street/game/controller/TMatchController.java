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
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITStageLifecycleService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 比赛场次
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/match")
public class TMatchController extends BaseController {

    private final ITMatchService tMatchService;
    private final ITMatchResultService tMatchResultService;
    private final ITStageLifecycleService tStageLifecycleService;

    /**
     * 查询比赛场次列表
     */
    @SaCheckPermission("game:match:list")
    @GetMapping("/list")
    public TableDataInfo<TMatchVo> list(TMatchBo bo, PageQuery pageQuery) {
        return tMatchService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出比赛场次列表
     */
    @SaCheckPermission("game:match:export")
    @Log(title = "比赛场次", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TMatchBo bo, HttpServletResponse response) {
        List<TMatchVo> list = tMatchService.queryList(bo);
        ExcelUtil.exportExcel(list, "比赛场次", TMatchVo.class, response);
    }

    /**
     * 获取比赛场次详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:match:query")
    @GetMapping("/{id}")
    public R<TMatchVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tMatchService.queryById(id));
    }

    /**
     * 新增比赛场次
     */
    @SaCheckPermission("game:match:add")
    @Log(title = "比赛场次", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TMatchVo> add(@Validated(AddGroup.class) @RequestBody TMatchBo bo) {
        return R.ok(tMatchService.insertByBo(bo));
    }

    /**
     * 修改比赛场次
     */
    @SaCheckPermission("game:match:edit")
    @Log(title = "比赛场次", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TMatchVo> edit(@Validated(EditGroup.class) @RequestBody TMatchBo bo) {
        return R.ok(tMatchService.updateByBo(bo));
    }

    /**
     * 删除比赛场次
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:match:remove")
    @Log(title = "比赛场次", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tMatchService.deleteWithValidByIds(List.of(ids), true));
    }

    // ==================== 比赛结果提交(裁判台 / 批量共用)====================

    /**
     * 提交比赛结果:写明细分 → 算分算排名 → 回写 → 赛制特有后处理 → 场次 SETTLED
     */
    @SaCheckPermission("game:match:edit")
    @Log(title = "提交比赛结果", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/submit-result")
    public R<MatchResultVo> submitResult(@NotNull(message = "场次ID不能为空")
                                         @PathVariable Long id,
                                         @RequestBody SubmitResultBo bo) {
        bo.setMatchId(id);
        return R.ok(tMatchResultService.submitResult(bo));
    }

    /**
     * 导播台确认公布结果(MANUAL 模式):用裁判判完暂存的结果结算场次
     */
    @SaCheckPermission("game:match:edit")
    @Log(title = "公布比赛结果", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/publish-result")
    public R<MatchResultVo> publishResult(@NotNull(message = "场次ID不能为空")
                                          @PathVariable Long id) {
        return R.ok(tMatchResultService.publishResult(id));
    }

    /**
     * 开始指定场次:PENDING → GAMING(赛段未开始则随场次开始),其余场次保持 PENDING。
     * 用于跳过其他场次、先开始指定场次。
     */
    @SaCheckPermission("game:match:edit")
    @Log(title = "开始场次", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/start")
    public R<Void> start(@NotNull(message = "场次ID不能为空") @PathVariable Long id) {
        tMatchResultService.startMatch(id);
        return R.ok();
    }

    /**
     * 查询场次当前标记的上场选手(海选大屏 widget 用):导播台在手机端标记,大屏读取
     */
    @SaCheckPermission("game:match:list")
    @GetMapping("/{id}/current-competitor")
    public R<Long> currentCompetitor(@NotNull(message = "场次ID不能为空") @PathVariable Long id) {
        return R.ok(tStageLifecycleService.getMatchCurrentCompetitor(id));
    }

    /**
     * 回退单场结算(调试用):级联清下游占位、清本场分数、场次回 GAMING
     */
    @SaCheckPermission("game:match:edit")
    @Log(title = "重置比赛结果", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/reset")
    public R<Void> reset(@NotNull(message = "场次ID不能为空") @PathVariable Long id) {
        tMatchResultService.resetMatch(id);
        return R.ok();
    }
}
