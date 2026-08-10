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
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.service.ITTournamentService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * 赛事主
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/tournament")
public class TTournamentController extends BaseController {

    private final ITTournamentService tTournamentService;

    /**
     * 查询赛事主列表
     */
    @SaCheckPermission("game:tournament:list")
    @GetMapping("/list")
    public TableDataInfo<TTournamentVo> list(TTournamentBo bo, PageQuery pageQuery) {
        return tTournamentService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出赛事主列表
     */
    @SaCheckPermission("game:tournament:export")
    @Log(title = "赛事主", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TTournamentBo bo, HttpServletResponse response) {
        List<TTournamentVo> list = tTournamentService.queryList(bo);
        ExcelUtil.exportExcel(list, "赛事主", TTournamentVo.class, response);
    }

    /**
     * 获取赛事主详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:tournament:query")
    @GetMapping("/{id}")
    public R<TTournamentVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tTournamentService.queryById(id));
    }

    /**
     * 新增赛事主
     */
    @SaCheckPermission("game:tournament:add")
    @Log(title = "赛事主", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TTournamentVo> add(@Validated(AddGroup.class) @RequestBody TTournamentBo bo) {
        return R.ok(tTournamentService.insertByBo(bo));
    }

    /**
     * 按模版创建赛事:自动创建赛事 + 赛段链 + 场景(主视觉/对战) + 对战树 widget 关联
     */
    @SaCheckPermission("game:tournament:add")
    @Log(title = "赛事主", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/create-by-template")
    public R<TTournamentVo> createByTemplate(@Validated @RequestBody TTournamentTemplateBo bo) {
        return R.ok(tTournamentService.createByTemplate(bo));
    }

    /**
     * 修改赛事主
     */
    @SaCheckPermission("game:tournament:edit")
    @Log(title = "赛事主", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TTournamentVo> edit(@Validated(EditGroup.class) @RequestBody TTournamentBo bo) {
        return R.ok(tTournamentService.updateByBo(bo));
    }

    /**
     * 删除赛事主
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:tournament:remove")
    @Log(title = "赛事主", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tTournamentService.deleteWithValidByIds(List.of(ids), true));
    }

    /**
     * 获取赛事登录凭证
     *
     * @param id 主键
     */
    @SaCheckPermission("game:tournament:query")
    @GetMapping("/authKey/{id}")
    public R<Map<String, String>> getAuthKey(@NotNull(message = "主键不能为空")
                                              @PathVariable Long id) {
        return R.ok(authKeyMap(tTournamentService.getAuthKeyById(id)));
    }

    /**
     * 生成新的赛事登录凭证
     *
     * @param id 主键
     */
    @SaCheckPermission("game:tournament:edit")
    @Log(title = "赛事主", businessType = BusinessType.UPDATE)
    @PutMapping("/authKey/{id}")
    public R<Map<String, String>> regenerateAuthKey(@NotNull(message = "主键不能为空")
                                                     @PathVariable Long id) {
        return R.ok(authKeyMap(tTournamentService.regenerateAuthKey(id)));
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
