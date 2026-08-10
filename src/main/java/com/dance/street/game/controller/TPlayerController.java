package com.dance.street.game.controller;

import java.io.IOException;
import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.web.core.BaseController;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.excel.utils.ExcelUtil;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.service.ITPlayerService;
import org.dromara.common.mybatis.core.page.TableDataInfo;

/**
 * 选手自然人
 *
 * @author duane
 * @date 2026-01-06
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/player")
public class TPlayerController extends BaseController {

    private final ITPlayerService tPlayerService;

    /**
     * 查询选手自然人列表
     */
    @SaCheckPermission("game:player:list")
    @GetMapping("/list")
    public TableDataInfo<TPlayerVo> list(TPlayerBo bo, PageQuery pageQuery) {
        return tPlayerService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出选手自然人列表
     */
    @SaCheckPermission("game:player:export")
    @Log(title = "选手自然人", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TPlayerBo bo, HttpServletResponse response) {
        List<TPlayerVo> list = tPlayerService.queryList(bo);
        ExcelUtil.exportExcel(list, "选手自然人", TPlayerVo.class, response);
    }

    /**
     * 获取选手自然人详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("game:player:query")
    @GetMapping("/{id}")
    public R<TPlayerVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(tPlayerService.queryById(id));
    }

    /**
     * 新增选手自然人
     */
    @SaCheckPermission("game:player:add")
    @Log(title = "选手自然人", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TPlayerVo> add(@Validated(AddGroup.class) @RequestBody TPlayerBo bo) {
        return R.ok(tPlayerService.insertByBo(bo));
    }

    /**
     * 修改选手自然人
     */
    @SaCheckPermission("game:player:edit")
    @Log(title = "选手自然人", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TPlayerVo> edit(@Validated(EditGroup.class) @RequestBody TPlayerBo bo) {
        return R.ok(tPlayerService.updateByBo(bo));
    }

    /**
     * 删除选手自然人
     *
     * @param ids 主键串
     */
    @SaCheckPermission("game:player:remove")
    @Log(title = "选手自然人", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(tPlayerService.deleteWithValidByIds(List.of(ids), true));
    }

    /**
     * 选手签到
     *
     * @param bo 签到请求
     * @return 签到后的选手信息
     */
    @SaCheckPermission("game:player:checkin")
    @Log(title = "选手签到", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/checkin")
    public R<TPlayerVo> checkIn(@Validated @RequestBody CheckInBo bo) {
        return R.ok(tPlayerService.checkIn(bo));
    }

    /**
     * 批量导入选手
     *
     * @param file         Excel文件
     * @param tournamentId 赛事ID
     */
    @SaCheckPermission("game:player:add")
    @Log(title = "选手自然人", businessType = BusinessType.IMPORT)
    @PostMapping("/import")
    public R<String> importPlayers(@RequestParam("file") MultipartFile file,
                                   @RequestParam("tournamentId") Long tournamentId) {
        if (file.isEmpty()) {
            return R.fail("请选择文件");
        }
        try {
            List<PlayerImportVo> list = ExcelUtil.importExcel(file.getInputStream(), PlayerImportVo.class);
            if (list.isEmpty()) {
                return R.fail("文件中没有数据");
            }
            int count = tPlayerService.importPlayers(list, tournamentId);
            return R.ok("成功导入 " + count + " 位选手");
        } catch (IOException e) {
            return R.fail("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 下载导入模板
     */
    @SaCheckPermission("game:player:query")
    @PostMapping("/import-template")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "选手导入模板", PlayerImportVo.class, response);
    }
}
