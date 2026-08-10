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
import com.dance.street.game.domain.vo.TMatchRoundVo;
import com.dance.street.game.domain.bo.TMatchRoundBo;
import com.dance.street.game.service.ITMatchRoundService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 比赛轮次
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/matchRound")
public class TMatchRoundController extends BaseController {

    private final ITMatchRoundService tMatchRoundService;

    /**
     * 查询比赛轮次列表
     */
    @SaCheckPermission("game:matchRound:list")
    @GetMapping("/list")
    public TableDataInfo<TMatchRoundVo> list(TMatchRoundBo bo, PageQuery pageQuery) {
        return tMatchRoundService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出比赛轮次列表
     */
    @SaCheckPermission("game:matchRound:export")
    @Log(title = "比赛轮次", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TMatchRoundBo bo, HttpServletResponse response) {
        List<TMatchRoundVo> list = tMatchRoundService.queryList(bo);
        ExcelUtil.exportExcel(list, "比赛轮次", TMatchRoundVo.class, response);
    }

    /**
     * 获取比赛轮次详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:matchRound:query")
    @GetMapping("/{id}")
    public R<TMatchRoundVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tMatchRoundService.queryById(id));
    }

    /**
     * 新增比赛轮次
     */
    @SaCheckPermission("game:matchRound:add")
    @Log(title = "比赛轮次", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TMatchRoundVo> add(@Validated(AddGroup.class) @RequestBody TMatchRoundBo bo) {
        return R.ok(tMatchRoundService.insertByBo(bo));
    }

    /**
     * 修改比赛轮次
     */
    @SaCheckPermission("game:matchRound:edit")
    @Log(title = "比赛轮次", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TMatchRoundVo> edit(@Validated(EditGroup.class) @RequestBody TMatchRoundBo bo) {
        return R.ok(tMatchRoundService.updateByBo(bo));
    }

    /**
     * 删除比赛轮次
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:matchRound:remove")
    @Log(title = "比赛轮次", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tMatchRoundService.deleteWithValidByIds(List.of(ids), true));
    }
}
