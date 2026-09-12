package com.dance.street.game.engine.common;

/**
 * 名单(roster)常量。
 * <p>名单是赛段属性:来源组规则 + 人工覆盖 + 快照,见设计文档
 * {@code design/roster-rule-snapshot.md}。</p>
 */
public final class RosterConstants {

    private RosterConstants() {
    }

    /** 名单状态 */
    public static final String ROSTER_WAIT_SOURCE = "WAIT_SOURCE";
    public static final String ROSTER_READY = "READY";
    public static final String ROSTER_CONFIRMED = "CONFIRMED";
    public static final String ROSTER_SKIPPED = "SKIPPED";

    /** 填充方式 */
    public static final String FILL_AUTO = "AUTO";
    public static final String FILL_MANUAL = "MANUAL";
    public static final String FILL_STREAM = "STREAM";

    /** 入场性质(entry_tag) */
    public static final String ENTRY_ADVANCE = "ADVANCE";
    public static final String ENTRY_REVIVE = "REVIVE";
    public static final String ENTRY_GUEST = "GUEST";
    public static final String ENTRY_MANUAL = "MANUAL";
    public static final String ENTRY_CHECKIN = "CHECKIN";

    /** result_filter 取值:ANY 表示不限结果(与 OutcomeStatusEnum 互补) */
    public static final String FILTER_ANY = "ANY";

    /** 外部来源(签到/GUEST/手动)的默认优先级:最高,先填充且优先保留 */
    public static final int EXTERNAL_ROSTER_PRIORITY = 0;

    /** 覆盖操作:把某源行拉进名单(即使规则未选中) */
    public static final String OVERRIDE_ADD_SOURCE = "ADD_SOURCE";
    /** 覆盖操作:无源外卡(可关联选手或纯姓名) */
    public static final String OVERRIDE_ADD_GUEST = "ADD_GUEST";
    /** 覆盖操作:从名单剔除某源行(规则选中的也不带) */
    public static final String OVERRIDE_REMOVE = "REMOVE";
    /** 覆盖操作:固定某源行/外卡的种子位 */
    public static final String OVERRIDE_SEED = "SEED";

    /** 组排序键:源赛段结算全局名次升序(默认) */
    public static final String ORDER_FINAL_RANK = "FINAL_RANK";
    /** 组排序键:按圈分组、圈内名次升序 */
    public static final String ORDER_ZONE_RANK = "ZONE_RANK";
    /** 组排序键:跨圈按圈内名次轮转交叉(圈1第1、圈2第1、圈1第2…) */
    public static final String ORDER_ZONE_RANK_ROTATE = "ZONE_RANK_ROTATE";
    /** 组排序键:分数降序 */
    public static final String ORDER_SCORE = "SCORE";
    /** 组排序键:参赛号码升序 */
    public static final String ORDER_NUMBER = "NUMBER";
    /** 组排序键:稳定随机(按源行 id 伪随机,保证预览与落位一致) */
    public static final String ORDER_RANDOM = "RANDOM";
}
