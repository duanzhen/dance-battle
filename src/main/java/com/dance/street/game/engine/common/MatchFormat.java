package com.dance.street.game.engine.common;

/**
 * 比赛格式(ruleConfig.format):一轮对阵打几局、先赢几局获胜。
 *
 * <p>与打分配置正交:{@code scoring}(WIN_LOSS_DRAW/TOTAL_SCORE/…)决定「每局怎么判胜负」,
 * 本枚举决定「最多打几局、赢几局算赢下这一轮」。旧配置/未配置回退 BO1。</p>
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

    /** 最多需要打几局(含平局加打) */
    public int maxGames() {
        return maxGames;
    }

    /** 先赢几局即赢下本轮对阵 */
    public int requiredWins() {
        return requiredWins;
    }

    /** BO1/BO3/BO5;未知或为空回退 BO1 */
    public static MatchFormat fromCode(String code) {
        if (code == null) {
            return BO1;
        }
        for (MatchFormat f : values()) {
            if (f.name().equalsIgnoreCase(code.trim())) {
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
