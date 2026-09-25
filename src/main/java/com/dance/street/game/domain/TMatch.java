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
     * 显示分区:LEFT/RIGHT(淘汰赛上下半区)、CENTER(季军赛/排名赛)、
     * ZONE-n(海选第 n 圈)、G1..Gn(小组赛分组)
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
     * 场次性质:NORMAL=正常场次(圈/对阵/擂台/自由对抗),TIEBREAKER=同分加赛(二海/三海…)。
     * 加赛判断一律以本列为准,不依赖备注文本。
     */
    private String matchType;

    /**
     * 加赛场次的来源场次(哪一场的同分边界需要它):仅 TIEBREAKER 有值。
     */
    private Long parentMatchId;

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
