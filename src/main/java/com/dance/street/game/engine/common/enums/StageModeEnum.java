package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;

/**
 * 赛段模式(赛制类型)。
 * <p>与前端 game-ui/src/views/game/tournament/stages/types.ts 的 StageMode 枚举对齐。</p>
 */
@Getter
@AllArgsConstructor
public enum StageModeEnum {

    AUDITION("AUDITION", "选拔赛"),
    KNOCKOUT("KNOCKOUT", "淘汰赛"),
    ARENA("ARENA", "擂台赛"),
    
    GROUP("GROUP", "小组赛"),
    RANK("RANK", "排名赛");



    private final String code;
    private final String desc;

    public static StageModeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (StageModeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new ServiceException("未知的赛段模式: {}", code);
    }
}
