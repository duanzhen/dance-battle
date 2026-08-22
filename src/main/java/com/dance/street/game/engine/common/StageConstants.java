package com.dance.street.game.engine.common;

/**
 * 赛事流程状态机常量(对应 t_stage.status / t_match.status / t_match_round.status 等 String 字段)。
 * <p>状态流转:赛段 DRAFT→PENDING→GAMING→SETTLED(→DISCARD);比赛 PENDING→GAMING→SETTLED。</p>
 */
public final class StageConstants {

    private StageConstants() {
    }

    /** 赛段状态 */
    public static final String STAGE_DRAFT = "DRAFT";
    public static final String STAGE_PENDING = "PENDING";
    public static final String STAGE_GAMING = "GAMING";
    public static final String STAGE_SETTLED = "SETTLED";
    public static final String STAGE_DISCARD = "DISCARD";

    /** 比赛场次状态(t_match / t_match_round 共用) */
    public static final String MATCH_PENDING = "PENDING";
    public static final String MATCH_GAMING = "GAMING";
    public static final String MATCH_SETTLED = "SETTLED";

    /** promotion_rule 中的 action 取值 */
    public static final String ACTION_ADVANCE = "ADVANCE";
    public static final String ACTION_FINAL_ADVANCE = "FINAL_ADVANCE";
    public static final String ACTION_ELIMINATE = "ELIMINATE";

    /** TRoundScore.action 取值 */
    public static final String SCORE_ACTION_SCORE = "SCORE";
    public static final String SCORE_ACTION_VOTE = "VOTE";

    /** 默认打分维度 */
    public static final String DIMENSION_MAIN = "MAIN";

    /** 比赛格式 */
    public static final String FORMAT_BO1 = "BO1";

    /** 插入赛段标记:作为嘉宾/外卡插入的赛段,写入 t_stage.remark,用于幂等与撤销识别 */
    public static final String GUEST_INSERT_REMARK = "GUEST_INSERT";
}
