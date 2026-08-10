package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 生成对阵请求:按赛制生成比赛场次。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class GenerateMatchesBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    /** 覆盖 ruleConfig 后再生成的可选配置 */
    private String ruleConfig;
}
