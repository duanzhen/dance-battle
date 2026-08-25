package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 赛段间转场配置(ruleConfig.transition)。决定本赛段结算后晋级如何触发。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitionConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标赛段ID(可选,默认取本赛段 nextStageId) */
    private Long targetStageId;

    /** 手动种子覆盖:competitorId -> 目标种子位(中间态调整预排后保存,开始赛段时生效) */
    private Map<Long, Long> seedOverrides;
}
