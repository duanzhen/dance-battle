package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 赛段级参赛单位结果(对应 TCompetitor.outcome_status)。
 * <p>与前端 StageCompetitorList 的 outcomeStatusMap 对齐。</p>
 */
@Getter
@AllArgsConstructor
public enum OutcomeStatusEnum {

    PENDING("PENDING", "进行中"),
    ADVANCE("ADVANCE", "晋级"),
    ELIMINATED("ELIMINATED", "淘汰"),
    WITHDRAWN("WITHDRAWN", "退赛");

    private final String code;
    private final String desc;

    public static OutcomeStatusEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (OutcomeStatusEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的赛段结果状态: {}", code);
    }
}
