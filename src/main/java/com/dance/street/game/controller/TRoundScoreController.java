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
import com.dance.street.game.domain.vo.TRoundScoreVo;
import com.dance.street.game.domain.bo.TRoundScoreBo;
import com.dance.street.game.service.ITRoundScoreService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 轮次打分结果
 *
 * @author duane
 * @date 2026-01-11
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/roundScore")
public class TRoundScoreController extends BaseController {

    private final ITRoundScoreService tRoundScoreService;

    /**
     * 查询轮次打分结果列表
     */
    @SaCheckPermission("game:roundScore:list")
    @GetMapping("/list")
    public TableDataInfo<TRoundScoreVo> list(TRoundScoreBo bo, PageQuery pageQuery) {
        return tRoundScoreService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出轮次打分结果列表
     */
    @SaCheckPermission("game:roundScore:export")
    @Log(title = "轮次打分结果", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TRoundScoreBo bo, HttpServletResponse response) {
        List<TRoundScoreVo> list = tRoundScoreService.queryList(bo);
        ExcelUtil.exportExcel(list, "轮次打分结果", TRoundScoreVo.class, response);
    }

    /**
     * 获取轮次打分结果详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:roundScore:query")
    @GetMapping("/{id}")
    public R<TRoundScoreVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tRoundScoreService.queryById(id));
    }

    /**
     * 新增轮次打分结果
     */
    @SaCheckPermission("game:roundScore:add")
    @Log(title = "轮次打分结果", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody TRoundScoreBo bo) {
        return toAjax(tRoundScoreService.insertByBo(bo));
    }

    /**
     * 修改轮次打分结果
     */
    @SaCheckPermission("game:roundScore:edit")
    @Log(title = "轮次打分结果", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody TRoundScoreBo bo) {
        return toAjax(tRoundScoreService.updateByBo(bo));
    }

    /**
     * 删除轮次打分结果
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:roundScore:remove")
    @Log(title = "轮次打分结果", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tRoundScoreService.deleteWithValidByIds(List.of(ids), true));
    }
}
