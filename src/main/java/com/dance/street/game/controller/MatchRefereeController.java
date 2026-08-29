package com.dance.street.game.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.dance.street.game.domain.bo.MatchRefereeBo;
import com.dance.street.game.domain.vo.MatchRefereeVo;
import com.dance.street.game.service.ITMatchRefereeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 裁判-场次(圈)关联接口
 *
 * <p>海选分圈时用于把裁判绑定到具体圈(场次),大屏晋级名单按圈展示。</p>
 *
 * @author duane
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/match-referee")
public class MatchRefereeController {

    private final ITMatchRefereeService matchRefereeService;

    /**
     * 分配某场次(圈)对应的裁判
     */
    @SaCheckPermission("game:matchReferee:edit")
    @Log(title = "场次裁判分配", businessType = BusinessType.UPDATE)
    @PostMapping("/assign")
    public R<Void> assign(@Validated @RequestBody MatchRefereeBo bo) {
        matchRefereeService.assign(bo);
        return R.ok();
    }

    /**
     * 查询某赛段各场次(圈)已分配的裁判
     */
    @SaCheckPermission("game:matchReferee:list")
    @GetMapping("/list")
    public R<List<MatchRefereeVo>> list(@RequestParam Long stageId) {
        return R.ok(matchRefereeService.listByStageId(stageId));
    }
}
