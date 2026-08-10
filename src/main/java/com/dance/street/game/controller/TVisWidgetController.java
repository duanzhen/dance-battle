package com.dance.street.game.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaIgnore;
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
import com.dance.street.game.domain.vo.TVisWidgetVo;
import com.dance.street.game.domain.bo.TVisWidgetBo;
import com.dance.street.game.domain.bo.ReorderBo;
import com.dance.street.game.service.ITVisWidgetService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 场景控件元素
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/visWidget")
public class TVisWidgetController extends BaseController {

    private final ITVisWidgetService tVisWidgetService;

    /**
     * 查询场景控件元素列表
     */
    @SaIgnore
    @GetMapping("/list")
    public TableDataInfo<TVisWidgetVo> list(TVisWidgetBo bo, PageQuery pageQuery) {
        return tVisWidgetService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出场景控件元素列表
     */
    @SaCheckPermission("game:visWidget:export")
    @Log(title = "场景控件元素", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TVisWidgetBo bo, HttpServletResponse response) {
        List<TVisWidgetVo> list = tVisWidgetService.queryList(bo);
        ExcelUtil.exportExcel(list, "场景控件元素", TVisWidgetVo.class, response);
    }

    /**
     * 获取场景控件元素详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:visWidget:query")
    @GetMapping("/{id}")
    public R<TVisWidgetVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tVisWidgetService.queryById(id));
    }

    /**
     * 图层排序:上移/下移交换相邻控件 zIndex(后端加锁原子)
     */
    @SaCheckPermission("game:visWidget:edit")
    @Log(title = "场景控件", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/layer")
    public R<Void> moveLayer(@NotNull(message = "主键不能为空") @PathVariable Long id,
                             @NotBlank(message = "方向不能为空") @RequestParam String dir) {
        tVisWidgetService.moveLayer(id, dir);
        return R.ok();
    }

    /**
     * 图层批量重排(拖动排序):按 widgetIds 顺序重分配 zIndex(加锁原子)
     */
    @SaCheckPermission("game:visWidget:edit")
    @Log(title = "场景控件", businessType = BusinessType.UPDATE)
    @PostMapping("/reorder")
    public R<Void> reorder(@RequestBody ReorderBo bo) {
        tVisWidgetService.reorder(bo.getSceneId(), bo.getWidgetIds());
        return R.ok();
    }

    /**
     * 新增场景控件元素
     */
    @SaCheckPermission("game:visWidget:add")
    @Log(title = "场景控件元素", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TVisWidgetVo> add(@Validated(AddGroup.class) @RequestBody TVisWidgetBo bo) {
        return R.ok(tVisWidgetService.insertByBo(bo));
    }

    /**
     * 修改场景控件元素
     */
    @SaCheckPermission("game:visWidget:edit")
    @Log(title = "场景控件元素", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TVisWidgetVo> edit(@Validated(EditGroup.class) @RequestBody TVisWidgetBo bo) {
        return R.ok(tVisWidgetService.updateByBo(bo));
    }

    /**
     * 删除场景控件元素
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:visWidget:remove")
    @Log(title = "场景控件元素", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tVisWidgetService.deleteWithValidByIds(List.of(ids), true));
    }
}
