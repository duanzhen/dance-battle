package com.dance.street.game.engine.common;

/**
 * 赛事流程状态机常量(对应 t_stage.status / t_match.status / t_match_round.status 等 String 字段)。
 * <p>状态流转:赛段 DRAFT→GAMING→SETTLED(→DISCARD);比赛/轮次 PENDING→GAMING→SETTLED。</p>
 *
 * <p>赛段的"初始化"只锁定名单/排种子,业务状态保持 DRAFT,「开始赛段」一步从 DRAFT 直达 GAMING。</p>
 *
 * @author duane
 */
public final class StageConstants {

    private StageConstants() {
    }

    /** 赛段状态 */
    public static final String STAGE_DRAFT = "DRAFT";
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

    /** TRoundScore.action 取值 */
    public static final String SCORE_ACTION_SCORE = "SCORE";
    public static final String SCORE_ACTION_VOTE = "VOTE";

    /** t_match.match_type 取值 */
    public static final String MATCH_TYPE_NORMAL = "NORMAL";
    /** 同分加赛(二海/三海…):由结算时按晋级线同分自动创建 */
    public static final String MATCH_TYPE_TIEBREAKER = "TIEBREAKER";

    /** 默认打分维度 */
    public static final String DIMENSION_MAIN = "MAIN";

    /**
     * t_match_participant.slot_kind 取值:座位类型。
     *
     * <p>每个座位都落一行,轮空不再"跳过"——否则参赛方数组下标 ≠ 座位下标,
     * 前端/下游按槽位还原位置时会错位(轮空侧的人被当成另一侧)。</p>
     */
    public static final String SLOT_PLAYER = "PLAYER";
    public static final String SLOT_BYE = "BYE";
    public static final String SLOT_PENDING = "PENDING";

}
