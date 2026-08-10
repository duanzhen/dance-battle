package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 单场比赛中某参赛方的本场结果(对应 TMatchParticipant.outcome_status)。
 * <p>STANDARD 打分模式下由比分判定 WIN/LOSS/DRAW;未结算为 PENDING。</p>
 */
@Getter
@AllArgsConstructor
public enum MatchOutcomeEnum {

    PENDING("PENDING", "未结算"),
    WIN("WIN", "胜"),
    LOSS("LOSS", "负"),
    DRAW("DRAW", "平");

    private final String code;
    private final String desc;

    public static MatchOutcomeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (MatchOutcomeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的比赛结果状态: {}", code);
    }
}
