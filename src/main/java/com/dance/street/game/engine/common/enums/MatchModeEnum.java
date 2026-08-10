package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 比赛模式(对应 TMatch.match_mode),决定该场采用哪种打分策略。
 * <p>STANDARD=判胜负平;VOTING=投票/总分计票;RANKING=多维度多裁判排名。</p>
 */
@Getter
@AllArgsConstructor
public enum MatchModeEnum {

    STANDARD("STANDARD", "标准(判胜负平)"),
    VOTING("VOTING", "投票计分"),
    RANKING("RANKING", "多维度排名");

    private final String code;
    private final String desc;

    public static MatchModeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (MatchModeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的比赛模式: {}", code);
    }
}
