package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 赛段"参赛方 → 所在圈"标签(海选阵容页专用只读视图)。
 *
 * <p>此前前端要发 5 个串行请求(取赛段、确保圈、列场次、列圈裁判、列参赛方)才能算出圈标签,
 * 其中还夹一次写库。这个视图由后端一次性批量算好返回,标签统一为 {@code A圈 / B圈 / …}。</p>
 *
 * @author duane
 */
@Data
public class StageCircleLabelsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;

    /** 赛段模式(前端据此判断是否海选,省掉一次 getStage) */
    private String stageMode;

    /** 赛段状态(前端用于签到锁定/进行中提示) */
    private String stageStatus;

    /** 圈数(按真实圈场次的 distinct 分区统计) */
    private int circleCount;

    /** 参赛方ID(字符串,避免雪花ID精度丢失)→ 圈标签(A圈/B圈…) */
    private Map<String, String> labels = new LinkedHashMap<>();
}
