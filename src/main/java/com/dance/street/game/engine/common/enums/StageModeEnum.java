package com.dance.street.game.engine.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;

/**
 * 赛段模式(赛制类型),同时是五种赛制之间的取值差异。
 * <p>与前端 frontend/src/views/game/tournament/stages/types.ts 的 StageMode 枚举对齐。
 * 算法差异(建树、结算)在对应的 {@code *Mode} 类,不在这里。</p>
 *
 * @author duane
 */
@Getter
@AllArgsConstructor
public enum StageModeEnum {

    AUDITION("AUDITION", "海选赛"),
    KNOCKOUT("KNOCKOUT", "淘汰赛"),
    ARENA("ARENA", "擂台赛"),
    RANK("RANK", "排名赛"),
    /** 自由对抗:线下抽签/指认确定对手,由手机导播台手动添加场次与晋级者,系统只记录对战与结果 */
    FREE_MATCH("FREE_MATCH", "自由对抗");

    /** 擂台赛拒绝生成对阵时的提示,与历史画像表文案一致。 */
    public static final String ARENA_NO_BRACKET = "擂台赛不生成对阵,开始赛段后由导播台逐场创建对决";

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

    /** 未知或空赛制直接报错(原 StageModeProfiles.of)。 */
    public static StageModeEnum require(String code) {
        StageModeEnum mode = fromCode(code);
        if (mode == null) {
            throw new ServiceException("未知的赛段模式: {}", code);
        }
        return mode;
    }

    public boolean audition() {
        return this == AUDITION;
    }

    public boolean rank() {
        return this == RANK;
    }

    public boolean knockout() {
        return this == KNOCKOUT;
    }

    public boolean arena() {
        return this == ARENA;
    }

    /** 海选、排名、淘汰会生成对阵;自由对抗静默跳过,擂台拒绝。 */
    public boolean generatesBracket() {
        return this == AUDITION || this == RANK || this == KNOCKOUT;
    }

    /** 擂台不预生成树,调用生成接口应报错。 */
    public boolean rejectsBracketGeneration() {
        return this == ARENA;
    }

    /** 海选与排名:一次提交只写部分选手,完成赛段才结算。 */
    public boolean perCompetitor() {
        return this == AUDITION || this == RANK;
    }

    /** 淘汰赛同时只允许一场进行中。 */
    public boolean singleActiveMatch() {
        return this == KNOCKOUT;
    }

    /** 擂台开赛后自动创建第一场。 */
    public boolean autoStartFirstMatch() {
        return this == ARENA;
    }

    /**
     * 生成场次时写入的 matchMode。
     * 海选固定投票,排名固定多维;其余取规则配置,缺省用 fallback。
     */
    public String resolveMatchMode(String configured, String fallback) {
        if (this == AUDITION) {
            return MatchModeEnum.VOTING.getCode();
        }
        if (this == RANK) {
            return MatchModeEnum.RANKING.getCode();
        }
        return StringUtils.isNotBlank(configured) ? configured : fallback;
    }
}
