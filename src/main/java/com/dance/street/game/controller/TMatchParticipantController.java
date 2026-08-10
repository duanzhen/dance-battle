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
import com.dance.street.game.domain.vo.TMatchParticipantVo;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import com.dance.street.game.service.ITMatchParticipantService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 场次参赛人员记录
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/matchParticipant")
public class TMatchParticipantController extends BaseController {

    private final ITMatchParticipantService tMatchParticipantService;

    /**
     * 查询场次参赛人员记录列表
     */
    @SaCheckPermission("game:matchParticipant:list")
    @GetMapping("/list")
    public TableDataInfo<TMatchParticipantVo> list(TMatchParticipantBo bo, PageQuery pageQuery) {
        return tMatchParticipantService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出场次参赛人员记录列表
     */
    @SaCheckPermission("game:matchParticipant:export")
    @Log(title = "场次参赛人员记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TMatchParticipantBo bo, HttpServletResponse response) {
        List<TMatchParticipantVo> list = tMatchParticipantService.queryList(bo);
        ExcelUtil.exportExcel(list, "场次参赛人员记录", TMatchParticipantVo.class, response);
    }

    /**
     * 获取场次参赛人员记录详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:matchParticipant:query")
    @GetMapping("/{id}")
    public R<TMatchParticipantVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tMatchParticipantService.queryById(id));
    }

    /**
     * 新增场次参赛人员记录
     */
    @SaCheckPermission("game:matchParticipant:add")
    @Log(title = "场次参赛人员记录", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TMatchParticipantVo> add(@Validated(AddGroup.class) @RequestBody TMatchParticipantBo bo) {
        return R.ok(tMatchParticipantService.insertByBo(bo));
    }

    /**
     * 修改场次参赛人员记录
     */
    @SaCheckPermission("game:matchParticipant:edit")
    @Log(title = "场次参赛人员记录", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TMatchParticipantVo> edit(@Validated(EditGroup.class) @RequestBody TMatchParticipantBo bo) {
        return R.ok(tMatchParticipantService.updateByBo(bo));
    }

    /**
     * 删除场次参赛人员记录
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:matchParticipant:remove")
    @Log(title = "场次参赛人员记录", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tMatchParticipantService.deleteWithValidByIds(List.of(ids), true));
    }
}
