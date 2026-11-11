package com.dance.street.game.engine.common;

/**
 * 比赛格式(ruleConfig.format):一轮对阵打几局、先赢几局获胜。
 *
 * <p>与打分配置正交:{@code scoring}(WIN_LOSS_DRAW/TOTAL_SCORE/…)决定「每局怎么判胜负」,
 * 本枚举决定「最多打几局、赢几局算赢下这一轮」。旧配置/未配置回退 BO1。</p>
 *
 * <p>淘汰赛开赛时可传入本场临时 BO(不写赛段配置),并落在 {@code t_match.format} 上。
 * 后续判罚、局分、重置一律读本场 format;为空(尚未开赛)时回退赛段默认。
 * 平局加赛补出第 4/5 局不会改变本场 format,BO3 仍是先赢 2 局。</p>
 *
 * @author duane
 */
public enum MatchFormat {

    BO1(1, 1),
    BO3(3, 2),
    BO5(5, 3);

    private final int maxGames;
    private final int requiredWins;

    MatchFormat(int maxGames, int requiredWins) {
        this.maxGames = maxGames;
        this.requiredWins = requiredWins;
    }

    /** 系列最多打几局 */
    public int maxGames() {
        return maxGames;
    }

    /** 先赢几局即可获胜 */
    public int requiredWins() {
        return requiredWins;
    }

    /** 是否为合法的显式 BO 编码(BO1/BO3/BO5);空白不算 */
    public static boolean isValidCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (MatchFormat f : values()) {
            if (f.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本场已写入的 format;空或非法则回退 {@code fallback}(通常是赛段默认)。
     */
    public static MatchFormat ofStored(String code, MatchFormat fallback) {
        if (!isValidCode(code)) {
            return fallback == null ? BO1 : fallback;
        }
        return fromCode(code);
    }

    /** BO1/BO3/BO5;未知或为空回退 BO1 */
    public static MatchFormat fromCode(String code) {
        if (code == null) {
            return BO1;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return BO1;
        }
        for (MatchFormat f : values()) {
            if (f.name().equalsIgnoreCase(trimmed)) {
                return f;
            }
        }
        return BO1;
    }

    /** 从赛段规则配置解析(读顶层 {@code format});解析失败回退 BO1 */
    public static MatchFormat of(String ruleConfig) {
        try {
            RuleConfigHolder rc = RuleConfigParser.parse(ruleConfig);
            if (rc == null) {
                return BO1;
            }
            // 顶层 format 优先;兼容个别配置把 format 写在 knockout 段内
            String code = rc.getFormat();
            if ((code == null || code.isBlank()) && rc.getKnockout() != null) {
                code = rc.getKnockout().getFormat();
            }
            return fromCode(code);
        } catch (RuntimeException e) {
            return BO1;
        }
    }
}
