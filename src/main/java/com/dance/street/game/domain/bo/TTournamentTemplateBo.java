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

    /** 主题配置 JSON(含赛事级红蓝配色 bracketColorOrder/matchColorOrder;为空用默认) */
    private String themeConfig;

    /**
     * 封面图片URL
     */
    private String coverImage;

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

    /**
     * 自动创建的裁判姓名列表;按模版创建时这些裁判会自动绑定到所有赛段;
     * 非空时优先于 refereeCount
     */
    private java.util.List<String> refereeNames;
}
