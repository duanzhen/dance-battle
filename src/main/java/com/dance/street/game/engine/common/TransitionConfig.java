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

    /** 转场模式,见 TransitionModeEnum,默认 AUTO */
    private String mode;
    /** 是否重新抽签(false 则按上赛段 finalRank 作为种子) */
    private Boolean reshuffle;
    /** 是否允许替补更换 */
    private Boolean allowSubstitutions;
    /** 目标赛段ID(可选,默认取本赛段 nextStageId) */
    private Long targetStageId;

    /** 手动种子覆盖:competitorId -> 目标种子位(中间态调整预排后保存,开始赛段时生效) */
    private Map<Long, Long> seedOverrides;
}
