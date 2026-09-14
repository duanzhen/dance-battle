package com.dance.street.game.engine.common;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 赛段流转的共享口径工具:跨「赛段生命周期」与「名单装配」两个服务复用的纯函数。
 *
 * <p>晋级名额、圈归属这类取值此前在两个服务里各写一份实现,任何一处单独修正
 * 都会让两边口径分叉(例如加赛/名单按不同名额计算)。同源逻辑只保留这一份。</p>
 *
 * @author duane
 */
public final class StageFlowSupport {

    /** 纯 Map 反序列化:与既有实现一致,不使用雪花 ID 安全 mapper */
    private static final ObjectMapper RAW_MAPPER = new ObjectMapper();

    private StageFlowSupport() {
    }

    /**
     * 赛段晋级名额:取 ruleConfig.knockout.advanceCount,顶层 advanceCount 覆盖之。
     * 未配置或解析失败时回退 1。
     */
    public static int readStageAdvanceCount(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int advanceCount = 1;
        if (rc != null && rc.getKnockout() != null && rc.getKnockout().getAdvanceCount() != null) {
            advanceCount = rc.getKnockout().getAdvanceCount();
        }
        try {
            Map<String, Object> raw = RAW_MAPPER.readValue(stage.getRuleConfig(), Map.class);
            if (raw != null && raw.containsKey("advanceCount")) {
                advanceCount = ((Number) raw.get("advanceCount")).intValue();
            }
        } catch (Exception ignored) {
            // 保持已解析到的名额
        }
        return advanceCount;
    }

    /** 场次所属圈:displayZone 为空时视为 CENTER(未分圈时的唯一圈) */
    public static String zoneOf(TMatch match) {
        return match.getDisplayZone() == null ? "CENTER" : match.getDisplayZone();
    }
}
