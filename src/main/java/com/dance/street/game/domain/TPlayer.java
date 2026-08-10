package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.dance.street.game.domain.vo.TCompetitorVo;

import java.io.Serial;

/**
 * 选手自然人对象 t_player
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_player")
public class TPlayer extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     *
     */
    @TableId(value = "id")
    private Long id;

    /**
     *
     */
    private Long tournamentId;

    /**
     *
     */
    private String name;

    /**
     *
     */
    private String avatar;

    /**
     * 身份唯一标识
     */
    private String idCard;

    /**
     * 首个赛段参赛选手
     */
    private Long competitorId;

    /**
     * 标签: ["种子", "外卡"]
     */
    private String tags;

    /**
     * 备注
     */
    private String remark;


}
