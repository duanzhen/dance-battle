package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 赛段间转场(晋级触发)模式(ruleConfig.transition.mode)。
 * <p>AUTO=赛段结算后自动晋级并初始化下一赛段;MANUAL=需管理员显式调用 calculate-advancement。</p>
 */
@Getter
@AllArgsConstructor
public enum TransitionModeEnum {

    AUTO("AUTO", "自动晋级"),
    MANUAL("MANUAL", "手动确认");

    private final String code;
    private final String desc;

    public static TransitionModeEnum fromCode(String code) {
        if (code == null) {
            // 未配置时默认 AUTO
            return AUTO;
        }
        for (TransitionModeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return AUTO;
    }
}
