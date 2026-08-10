package com.dance.street.game.domain;

import org.dromara.common.tenant.core.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 比赛场次对象 t_match
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_match")
public class TMatch extends TenantEntity {

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
    private Long stageId;

    /**
     * 
     */
    private String name;

    /**
     * LEFT, RIGHT, CENTER
     */
    private String displayZone;

    /**
     * Y轴排序
     */
    private Long displayRow;

    /**
     * 
     */
    private Long displayCol;

    /**
     * 
     */
    private String status;

    /**
     * STANDARD, VOTING, RANKING
     */
    private String matchMode;

    /**
     * 
     */
    private String promotionRule;

    /**
     * 手动公布模式:裁判判完后暂存的结果(competitorId -> WIN/LOSS/DRAW),导播台公布后清空
     */
    private String resultJson;

    /**
     * 备注
     */
    private String remark;


}
