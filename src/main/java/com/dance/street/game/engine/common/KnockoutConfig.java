package com.dance.street.game.engine.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 淘汰赛配置(ruleConfig.knockout)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnockoutConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板:FINAL/SEMI_FINAL/QUARTER_FINAL/ROUND_16/ROUND_32/ROUND_64/CUSTOM */
    private String template;
    /** 参赛选手数 */
    private Integer teamsCount;
    /** 晋级到下一赛段的选手数 */
    private Integer advanceCount;
    /** 是否进行季军赛 */
    private Boolean thirdPlaceMatch;

    /** 单轮模式(每轮一赛段):只生成 N/2 场,胜者全部晋级下一赛段;false/null=单赛段多轮完整 bracket */
    private Boolean singleRound;

    /** 单轮配对模式:SEQUENTIAL(1-2、3-4 相邻)/ SEED(1-N、2-(N-1) 种子对位,海选赛后首轮常用) */
    private String pairingMode;

    /** 结果公布模式:AUTO(裁判判完自动公布)/ MANUAL(导播台确认后公布)/ DIRECTOR(导播台直接判定) */
    private String publishMode;
}
