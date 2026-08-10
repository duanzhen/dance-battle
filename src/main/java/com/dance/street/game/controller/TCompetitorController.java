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
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.service.ITCompetitorService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 参赛单位
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/competitor")
public class TCompetitorController extends BaseController {

    private final ITCompetitorService tCompetitorService;

    /**
     * 查询参赛单位列表
     */
    @SaCheckPermission("game:competitor:list")
    @GetMapping("/list")
    public TableDataInfo<TCompetitorVo> list(TCompetitorBo bo, PageQuery pageQuery) {
        return tCompetitorService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出参赛单位列表
     */
    @SaCheckPermission("game:competitor:export")
    @Log(title = "参赛单位", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TCompetitorBo bo, HttpServletResponse response) {
        List<TCompetitorVo> list = tCompetitorService.queryList(bo);
        ExcelUtil.exportExcel(list, "参赛单位", TCompetitorVo.class, response);
    }

    /**
     * 获取参赛单位详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:competitor:query")
    @GetMapping("/{id}")
    public R<TCompetitorVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tCompetitorService.queryById(id));
    }

    /**
     * 新增参赛单位
     */
    @SaCheckPermission("game:competitor:add")
    @Log(title = "参赛单位", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TCompetitorVo> add(@Validated(AddGroup.class) @RequestBody TCompetitorBo bo) {
        return R.ok(tCompetitorService.insertByBo(bo));
    }

    /**
     * 修改参赛单位
     */
    @SaCheckPermission("game:competitor:edit")
    @Log(title = "参赛单位", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TCompetitorVo> edit(@Validated(EditGroup.class) @RequestBody TCompetitorBo bo) {
        return R.ok(tCompetitorService.updateByBo(bo));
    }

    /**
     * 删除参赛单位
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:competitor:remove")
    @Log(title = "参赛单位", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tCompetitorService.deleteWithValidByIds(List.of(ids), true));
    }
}
