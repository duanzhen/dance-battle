package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 打分机制类型(ruleConfig.scoring.type)。
 * <p>与打分/轮次正交:本枚举决定「每局怎么判定胜负」,format(BO1/BO3)决定「打几局」。</p>
 */
@Getter
@AllArgsConstructor
public enum ScoreTypeEnum {

    WIN_LOSS_DRAW("WIN_LOSS_DRAW", "判胜负平"),
    TOTAL_SCORE("TOTAL_SCORE", "总分制"),
    MULTI_DIM("MULTI_DIM", "多维度评判");

    private final String code;
    private final String desc;

    public static ScoreTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ScoreTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的打分类型: {}", code);
    }
}
