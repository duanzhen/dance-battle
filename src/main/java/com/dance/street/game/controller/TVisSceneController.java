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
import com.dance.street.game.excel.ExcelUtil;
import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.bo.TVisSceneBo;
import com.dance.street.game.service.ITVisSceneService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 可视化场景配置
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/visScene")
public class TVisSceneController extends BaseController {

    private final ITVisSceneService tVisSceneService;

    /**
     * 查询可视化场景配置列表
     */
    @SaIgnore
    @GetMapping("/list")
    public TableDataInfo<TVisSceneVo> list(TVisSceneBo bo, PageQuery pageQuery) {
        return tVisSceneService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出可视化场景配置列表
     */
    @SaCheckPermission("game:visScene:export")
    @Log(title = "可视化场景配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TVisSceneBo bo, HttpServletResponse response) {
        List<TVisSceneVo> list = tVisSceneService.queryList(bo);
        ExcelUtil.exportExcel(list, "可视化场景配置", TVisSceneVo.class, response);
    }

    /**
     * 获取可视化场景配置详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:visScene:query")
    @GetMapping("/{id}")
    public R<TVisSceneVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tVisSceneService.queryById(id));
    }

    /**
     * 新增可视化场景配置
     */
    @SaCheckPermission("game:visScene:add")
    @Log(title = "可视化场景配置", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TVisSceneVo> add(@Validated(AddGroup.class) @RequestBody TVisSceneBo bo) {
        return R.ok(tVisSceneService.insertByBo(bo));
    }

    /**
     * 修改可视化场景配置
     */
    @SaCheckPermission("game:visScene:edit")
    @Log(title = "可视化场景配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TVisSceneVo> edit(@Validated(EditGroup.class) @RequestBody TVisSceneBo bo) {
        return R.ok(tVisSceneService.updateByBo(bo));
    }

    /**
     * 删除可视化场景配置
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:visScene:remove")
    @Log(title = "可视化场景配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tVisSceneService.deleteWithValidByIds(List.of(ids), true));
    }
}
