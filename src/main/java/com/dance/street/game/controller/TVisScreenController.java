package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.dance.street.game.domain.bo.TVisScreenBo;
import com.dance.street.game.domain.vo.TVisScreenVo;
import com.dance.street.game.service.ITVisScreenService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大屏屏幕配置(多控制端共用同一份屏幕列表)。
 *
 * <p>屏幕不再存在各控制端的浏览器里,而是按赛事持久化到 {@code t_vis_screen};
 * 任一控制端增删改后,服务端广播 {@code screenListChanged},其它控制端重新拉取。</p>
 *
 * @author duane
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/visScreen")
public class TVisScreenController extends BaseController {

    private final ITVisScreenService tVisScreenService;

    /**
     * 屏幕列表(空赛事自动补一块默认屏,保证控制端打开就有屏可用)
     */
    @SaIgnore
    @GetMapping("/list")
    public R<List<TVisScreenVo>> list(@NotNull(message = "赛事ID不能为空") @RequestParam("tournamentId") Long tournamentId) {
        return R.ok(tVisScreenService.listByTournament(tournamentId));
    }

    /**
     * 新增屏幕
     */
    @SaCheckPermission("game:visScreen:add")
    @Log(title = "大屏屏幕", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<TVisScreenVo> add(@Validated(AddGroup.class) @RequestBody TVisScreenBo bo) {
        return R.ok(tVisScreenService.insertByBo(bo));
    }

    /**
     * 修改屏幕(改名 / 排序)
     */
    @SaCheckPermission("game:visScreen:edit")
    @Log(title = "大屏屏幕", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<TVisScreenVo> edit(@Validated(EditGroup.class) @RequestBody TVisScreenBo bo) {
        return R.ok(tVisScreenService.updateByBo(bo));
    }

    /**
     * 删除屏幕
     */
    @SaCheckPermission("game:visScreen:remove")
    @Log(title = "大屏屏幕", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable("ids") Long[] ids) {
        return toAjax(tVisScreenService.deleteByIds(List.of(ids)));
    }
}
