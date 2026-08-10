package com.dance.street.game.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.service.ITRefereeStageService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 裁判-赛段关联接口
 *
 * @author duane
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/referee-stage")
public class RefereeStageController {

    private final ITRefereeStageService refereeStageService;

    /**
     * 查询某赛段已分配的裁判ID列表
     */
    @GetMapping("/referee-ids")
    public R<List<Long>> getRefereeIds(@RequestParam Long stageId) {
        return R.ok(refereeStageService.getRefereeIdsByStageId(stageId));
    }

    /**
     * 批量设置赛段裁判（全量替换）
     */
    @PostMapping("/assign")
    public R<Void> assignReferees(@Validated @RequestBody StageRefereeBo bo) {
        refereeStageService.assignReferees(bo);
        return R.ok();
    }
}
