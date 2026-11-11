package com.dance.street.game.engine.common;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import tools.jackson.databind.JsonNode;

/**
 * 排名赛打分公布状态,复用 {@code t_match.result_json},不新加表字段。
 *
 * <p>待公布:{@code {"scoresPublished":false}};已公布:{@code {"scoresPublished":true}}。
 * 淘汰赛/擂台赛的 result_json 是 competitorId→WIN/LOSS/DRAW,没有该字段,
 * 本工具一律视为「不是排名赛公布标记」。</p>
 *
 * <p>{@code pendingPublish} 对排名赛只在 {@code scoresPublished==false} 时为真;
 * 已公布后 result_json 仍保留,但不能再当成「待公布」,否则导播台按钮会一直亮。</p>
 */
public final class ScorePublishState {

    private ScorePublishState() {
    }

    /** 排名赛 MANUAL:裁判已打完,等待导播台公布 */
    public static final String PENDING_JSON = "{\"scoresPublished\":false}";

    /** 排名赛 MANUAL:导播台已公布,场次仍保持 GAMING,赛段尚未结算 */
    public static final String PUBLISHED_JSON = "{\"scoresPublished\":true}";

    /** 是否为排名赛「待公布」标记 */
    public static boolean isPending(String resultJson) {
        return Boolean.FALSE.equals(scoresPublished(resultJson));
    }

    /** 是否为排名赛「已公布」标记(场次可能仍是 GAMING) */
    public static boolean isPublished(String resultJson) {
        return Boolean.TRUE.equals(scoresPublished(resultJson));
    }

    /**
     * 读取 {@code scoresPublished};不是该格式(空、淘汰赛胜负 JSON 等)返回 null。
     */
    public static Boolean scoresPublished(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = SnowflakeJson.mapper().readTree(resultJson);
            if (node == null || !node.isObject() || !node.has("scoresPublished")) {
                return null;
            }
            JsonNode flag = node.get("scoresPublished");
            if (flag == null || flag.isNull()) {
                return null;
            }
            if (flag.isBoolean() || flag.isString()) {
                return flag.asBoolean();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导播台/裁判端「待公布」:进行中且 result_json 非空;
     * 排名赛还要求 {@code scoresPublished==false}(已公布不再算待公布)。
     */
    public static boolean pendingPublish(String stageMode, String matchStatus, String resultJson) {
        if (!StageConstants.MATCH_GAMING.equals(matchStatus)
            || resultJson == null || resultJson.isBlank()) {
            return false;
        }
        if (stageMode != null && StageModeEnum.require(stageMode).rank()) {
            return isPending(resultJson);
        }
        return true;
    }

    /**
     * 公共端是否应隐藏排名赛分数与名次:
     * MANUAL/BATCH 且赛段未 SETTLED,并且该场尚未 scoresPublished。
     * AUTO 或已公布(含赛段已结算)则露分。
     */
    public static boolean hideRankPublic(TStage stage, TMatch match) {
        if (stage == null || !StageModeEnum.require(stage.getStageMode()).rank()) {
            return false;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            return false;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        String mode = rc == null ? "AUTO" : rc.resolvePublishMode();
        if (mode == null || "AUTO".equalsIgnoreCase(mode)) {
            return false;
        }
        return match == null || !isPublished(match.getResultJson());
    }

    /**
     * 公共端是否应隐藏淘汰/擂台真实判罚:
     * MANUAL 且场次尚未 SETTLED(公布结果即结算)。AUTO 或已公布则露出。
     */
    public static boolean hideJudgementPublic(TStage stage, String matchStatus) {
        if (stage == null) {
            return false;
        }
        StageModeEnum profile = StageModeEnum.require(stage.getStageMode());
        if (!profile.knockout() && !profile.arena()) {
            return false;
        }
        if (StageConstants.MATCH_SETTLED.equals(matchStatus)) {
            return false;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        String mode = rc == null ? "AUTO" : rc.resolvePublishMode();
        return "MANUAL".equalsIgnoreCase(mode);
    }
}
