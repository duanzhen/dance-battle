package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.service.ITStageRosterService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
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
import java.util.Map;

/**
 * 赛段名单(roster)管理接口:名单是赛段属性,一律以 stageId 寻址。
 *
 * @author duane
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/stage")
public class TStageRosterController {

    private final ITStageRosterService rosterService;

    /** 出口视角:引用某来源赛段的名单列表 */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/roster/list")
    public R<List<TStageRosterVo>> listBySource(
        @RequestParam(value = "sourceStageId", required = false) Long sourceStageId) {
        if (sourceStageId == null) {
            throw new ServiceException("请指定 sourceStageId");
        }
        return R.ok(rosterService.listBySource(sourceStageId));
    }

    /** 目标赛段名单详情 */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/{stageId}/roster")
    public R<TStageRosterVo> detail(@PathVariable Long stageId) {
        List<TStageRosterVo> list = rosterService.listByTarget(stageId);
        if (list.isEmpty()) {
            throw new ServiceException("赛段名单不存在");
        }
        return R.ok(list.get(0));
    }

    /** 追加来源组(多组并集;幂等去重) */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "追加名单来源", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{stageId}/roster/groups")
    public R<TStageRosterVo> addGroups(@PathVariable Long stageId,
                                       @RequestBody TStageRosterBo bo) {
        return R.ok(rosterService.addGroups(stageId, bo));
    }

    /** 删除某一来源组 */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "删除名单来源组", businessType = BusinessType.UPDATE)
    @DeleteMapping("/{stageId}/roster/groups/{groupIndex}")
    public R<Void> removeGroup(@PathVariable Long stageId, @PathVariable int groupIndex) {
        rosterService.removeGroup(stageId, groupIndex);
        return R.ok();
    }

    /** 编辑某一来源组规则(出口/入口自定义配置共用) */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "更新名单来源组", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{stageId}/roster/groups/{groupIndex}")
    public R<Void> updateGroup(@PathVariable Long stageId, @PathVariable int groupIndex,
                               @RequestBody TStageRosterGroupBo group) {
        rosterService.updateGroup(stageId, groupIndex, group);
        return R.ok();
    }

    /** 整单装配(快照物化,唯一写库动作) */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "确认名单", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{stageId}/roster/apply")
    public R<Integer> apply(@PathVariable Long stageId, @RequestBody(required = false) ApplyBody body) {
        return R.ok(rosterService.applyRoster(stageId,
            body == null ? null : body.getManualSelections()));
    }

    /** 显式跳过(本赛段不带人) */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "跳过名单", businessType = BusinessType.UPDATE)
    @PostMapping("/{stageId}/roster/skip")
    public R<Void> skip(@PathVariable Long stageId) {
        rosterService.markSkipped(stageId);
        return R.ok();
    }

    /** 名单实时预览(规则 + 覆盖合并,只读) */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/{stageId}/roster/preview")
    public R<RosterPreviewVo> preview(@PathVariable Long stageId) {
        return R.ok(rosterService.previewAssembled(stageId));
    }

    /** 名单候选(按来源组返回,手动点选/预览) */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/{stageId}/roster/candidates")
    public R<RosterCandidatesVo> candidates(@PathVariable Long stageId) {
        return R.ok(rosterService.candidates(stageId));
    }

    /** 名单人工覆盖列表 */
    @SaCheckPermission("game:stage:list")
    @GetMapping("/{stageId}/roster/overrides")
    public R<List<TStageRosterOverrideVo>> overrides(@PathVariable Long stageId) {
        return R.ok(rosterService.listOverrides(stageId));
    }

    /** 新增人工覆盖 */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "新增名单人工覆盖", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/{stageId}/roster/overrides")
    public R<TStageRosterOverrideVo> addOverride(@PathVariable Long stageId,
                                                 @RequestBody TStageRosterOverrideBo bo) {
        return R.ok(rosterService.addOverride(stageId, bo));
    }

    /** 编辑人工覆盖 */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "编辑名单人工覆盖", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{stageId}/roster/overrides/{overrideId}")
    public R<Void> updateOverride(@PathVariable Long stageId, @PathVariable Long overrideId,
                                  @RequestBody TStageRosterOverrideBo bo) {
        rosterService.updateOverride(stageId, overrideId, bo);
        return R.ok();
    }

    /** 撤销人工覆盖 */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "撤销名单人工覆盖", businessType = BusinessType.UPDATE)
    @DeleteMapping("/{stageId}/roster/overrides/{overrideId}")
    public R<Void> deleteOverride(@PathVariable Long stageId, @PathVariable Long overrideId) {
        rosterService.deleteOverride(stageId, overrideId);
        return R.ok();
    }

    /** 保存手工名单顺序(中间态两列拖动结果) */
    @SaCheckPermission("game:stage:edit")
    @Log(title = "保存名单顺序", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{stageId}/roster/order")
    public R<Void> reorder(@PathVariable Long stageId, @RequestBody(required = false) TStageRosterOrderBo bo) {
        rosterService.reorderRoster(stageId, bo == null ? null : bo.getItems());
        return R.ok();
    }

    @Data
    public static class ApplyBody {

        /** 手动选中的源行 ID(兼容旧 MANUAL 整单点选入口) */
        private Map<Long, List<Long>> manualSelections;
    }
}
