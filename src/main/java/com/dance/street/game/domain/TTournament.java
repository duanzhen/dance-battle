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
     * 逻辑删除:0 未删除 1 已删除。
     *
     * <p>删除赛事只在主表打这个标记,下属数据(赛段/场景/控件/裁判/选手等)一律保留;
     * 所有基于本实体的查询会自动附加 {@code deleted = 0},因此已删除赛事不再出现在入口列表中。</p>
     */
    @TableLogic
    private Integer deleted;

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
