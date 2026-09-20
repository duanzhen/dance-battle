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
     * 场次性质:NORMAL=正常场次(圈/对阵/擂台/自由对抗),TIEBREAKER=同分加赛(二海/三海…)。
     *
     * <p>历史数据该列为 null,按 remark 前缀「同分加赛」兜底识别(见 SettlementSupport.isTiebreaker);
     * 新产生的加赛场次一律显式写入,判断不再依赖备注文本。</p>
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
