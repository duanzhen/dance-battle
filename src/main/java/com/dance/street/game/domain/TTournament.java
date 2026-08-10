package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 赛事主对象 t_tournament
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tournament")
public class TTournament extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     *
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 赛事名称
     */
    private String name;

    /**
     * 登录凭证
     */
    private String authKey;

    /**
     * 封面图片URL
     */
    private String coverImage;

    /**
     * 详情
     */
    private String description;

    /**
     * 0:筹备 1:进行中 2:结束
     */
    private Long status;

    /**
     * 设计稿宽度
     */
    private Long logicalWidth;

    /**
     * 设计稿高度
     */
    private Long logicalHeight;

    /**
     * {"bgColor": "#000", "fontFamily": "Roboto"}
     */
    private String themeConfig;

    /**
     * 备注
     */
    private String remark;


}
