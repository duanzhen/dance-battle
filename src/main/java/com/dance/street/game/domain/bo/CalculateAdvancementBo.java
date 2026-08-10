package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 计算晋级请求:从已结算赛段把晋级者推进下一赛段。
 *
 * @author duane
 * @date 2026-08-09
 */
@Data
public class CalculateAdvancementBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    /** 手动覆盖种子顺位:competitorId -> seedRank(可选) */
    private Map<Long, Long> seedOverrides;
}
