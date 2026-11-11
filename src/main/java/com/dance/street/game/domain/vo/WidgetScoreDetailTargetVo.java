package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评分详情组件当前推送目标(场次 + 选手)。
 * 存于跨实例缓存,不落库。
 *
 * @author duane
 */
@Data
public class WidgetScoreDetailTargetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 被推送的场次(圈)ID */
    private Long matchId;

    /** 被推送的选手 ID */
    private Long competitorId;
}
