package com.dance.street.game.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.domain.bo.TRefereeBo;
import com.dance.street.game.service.ITRefereeService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 裁判
 *
 * @author duane
 * @date 2026-01-11
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/referee")
public class TRefereeController extends BaseController {

    private final ITRefereeService tRefereeService;

    /**
     * 查询裁判列表
     */
    @SaCheckPermission("game:referee:list")
    @GetMapping("/list")
    public TableDataInfo<TRefereeVo> list(TRefereeBo bo, PageQuery pageQuery) {
        return tRefereeService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出裁判列表
     */
    @SaCheckPermission("game:referee:export")
    @Log(title = "裁判", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TRefereeBo bo, HttpServletResponse response) {
        List<TRefereeVo> list = tRefereeService.queryList(bo);
        ExcelUtil.exportExcel(list, "裁判", TRefereeVo.class, response);
    }

    /**
     * 获取裁判详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:referee:query")
    @GetMapping("/{id}")
    public R<TRefereeVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tRefereeService.queryById(id));
    }

    /**
     * 新增裁判
     */
    @SaCheckPermission("game:referee:add")
    @Log(title = "裁判", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody TRefereeBo bo) {
        return toAjax(tRefereeService.insertByBo(bo));
    }

    /**
     * 修改裁判
     */
    @SaCheckPermission("game:referee:edit")
    @Log(title = "裁判", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody TRefereeBo bo) {
        return toAjax(tRefereeService.updateByBo(bo));
    }

    /**
     * 删除裁判
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:referee:remove")
    @Log(title = "裁判", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tRefereeService.deleteWithValidByIds(List.of(ids), true));
    }

    /**
     * 获取裁判登录凭证
     *
     * @param id 主键
     */
    @SaCheckPermission("game:referee:query")
    @GetMapping("/authKey/{id}")
    public R<Map<String, String>> getAuthKey(@NotNull(message = "主键不能为空")
                                              @PathVariable Long id) {
        return R.ok(authKeyMap(tRefereeService.getAuthKeyById(id)));
    }

    /**
     * 生成新的裁判登录凭证
     *
     * @param id 主键
     */
    @SaCheckPermission("game:referee:edit")
    @Log(title = "裁判", businessType = BusinessType.UPDATE)
    @PutMapping("/authKey/{id}")
    public R<Map<String, String>> regenerateAuthKey(@NotNull(message = "主键不能为空")
                                                     @PathVariable Long id) {
        return R.ok(authKeyMap(tRefereeService.regenerateAuthKey(id)));
    }

    /**
     * 包装 authKey 为 Map:R.ok(String) 会被重载解析为「消息」放进 msg,
     * 必须包成对象才能正常返回在 data 字段。
     */
    private Map<String, String> authKeyMap(String authKey) {
        Map<String, String> map = new HashMap<>();
        map.put("authKey", authKey);
        return map;
    }
}
