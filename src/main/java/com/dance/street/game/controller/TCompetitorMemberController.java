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
import com.dance.street.game.domain.vo.TCompetitorMemberVo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.service.ITCompetitorMemberService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 参赛成员关联
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/competitorMember")
public class TCompetitorMemberController extends BaseController {

    private final ITCompetitorMemberService tCompetitorMemberService;

    /**
     * 查询参赛成员关联列表
     */
    @SaCheckPermission("game:competitorMember:list")
    @GetMapping("/list")
    public TableDataInfo<TCompetitorMemberVo> list(TCompetitorMemberBo bo, PageQuery pageQuery) {
        return tCompetitorMemberService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出参赛成员关联列表
     */
    @SaCheckPermission("game:competitorMember:export")
    @Log(title = "参赛成员关联", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TCompetitorMemberBo bo, HttpServletResponse response) {
        List<TCompetitorMemberVo> list = tCompetitorMemberService.queryList(bo);
        ExcelUtil.exportExcel(list, "参赛成员关联", TCompetitorMemberVo.class, response);
    }

    /**
     * 获取参赛成员关联详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:competitorMember:query")
    @GetMapping("/{id}")
    public R<TCompetitorMemberVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tCompetitorMemberService.queryById(id));
    }

    /**
     * 新增参赛成员关联
     */
    @SaCheckPermission("game:competitorMember:add")
    @Log(title = "参赛成员关联", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TCompetitorMemberVo> add(@Validated(AddGroup.class) @RequestBody TCompetitorMemberBo bo) {
        return R.ok(tCompetitorMemberService.insertByBo(bo));
    }

    /**
     * 修改参赛成员关联
     */
    @SaCheckPermission("game:competitorMember:edit")
    @Log(title = "参赛成员关联", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TCompetitorMemberVo> edit(@Validated(EditGroup.class) @RequestBody TCompetitorMemberBo bo) {
        return R.ok(tCompetitorMemberService.updateByBo(bo));
    }

    /**
     * 删除参赛成员关联
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:competitorMember:remove")
    @Log(title = "参赛成员关联", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tCompetitorMemberService.deleteWithValidByIds(List.of(ids), true));
    }
}
