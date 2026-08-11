package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 按模版创建赛事业务对象
 *
 * @author duane
 * @date 2026-08-09
 */
@Data
public class TTournamentTemplateBo {

    /**
     * 赛事名称
     */
    @NotBlank(message = "赛事名称不能为空")
    private String name;

    /**
     * 模版编码:AUDITION_32 / AUDITION_16 / AUDITION_ARENA
     */
    @NotBlank(message = "模版编码不能为空")
    private String templateCode;

    /**
     * 备注
     */
    private String remark;

    /**
     * 自动创建的裁判数量;按模版创建时这些裁判会自动绑定到所有赛段
     */
    private Integer refereeCount;
}
