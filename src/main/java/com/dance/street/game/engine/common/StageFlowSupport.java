package com.dance.street.game.engine.common;

import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import org.dromara.common.core.exception.ServiceException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 一个圈的结算上下文。
     *
     * @param ordinal 圈序号(0 基,按场次 displayRow 顺序)
     * @param quota   本圈晋级名额
     * @param base    本圈在全局名次里的起点(前序各圈名额累加)
     */
    public record CircleQuota(int ordinal, int quota, int base) {
    }

    /**
     * 圈名额上下文(唯一口径):{@code zone -> (圈序号, 本圈名额, 全局排名起点)}。
     *
     * <p>「每圈分几个晋级名额」「本圈名次从全局第几名开始」这两件事此前在三处各写了一遍
     * (海选结算、排名赛结算、名单按圈取人),任何一处单独改动都会让"结算名次"与
     * "名单取人顺序"分叉。这里作为同源实现,参数 {@code modeLabel} 只用于错误提示。</p>
     *
     * <p>规则:显式配置 {@code circleAdvanceCounts} 优先(逐圈可不同,长度不足回退均分值);
     * 未配置时按"实际圈数"均分并要求整除;配置圈数之外的残留场次按 0 名额处理。</p>
     *
     * @param stage     赛段(读 rule_config 与晋级名额)
     * @param matches   本赛段场次(按 displayRow、id 升序;加赛场次与正式圈同 zone,会被合并)
     * @param modeLabel 赛制名(如"海选""排名赛"),仅用于错误信息
     */
    public static Map<String, CircleQuota> circleQuotaContext(TStage stage, List<TMatch> matches, String modeLabel) {
        Map<String, CircleQuota> ctx = new LinkedHashMap<>();
        if (stage == null || matches == null || matches.isEmpty()) {
            return ctx;
        }
        int advanceCount = readStageAdvanceCount(stage);
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        if (explicitQuota) {
            for (Integer q : perCircleCfg) {
                if (q == null || q < 0) {
                    throw new ServiceException("{}每圈晋级人数配置非法(不能为负): {}", modeLabel, perCircleCfg);
                }
            }
        }
        // 圈数以实际场次为准:配置圈数可能被生成器按人数收缩,也可能在生成后被修改
        int circles = Math.max(1, (int) matches.stream().map(StageFlowSupport::zoneOf).distinct().count());
        int plannedCircles = rc != null && rc.getCircles() != null ? Math.max(1, rc.getCircles()) : circles;
        // 历史残留的"配置圈数之外"场次按 0 人晋级处理;正常名额按配置圈数均分
        int divideBy = Math.min(circles, plannedCircles);
        int perCircle = divideBy > 1 ? advanceCount / divideBy : advanceCount;
        if (!explicitQuota && circles <= plannedCircles && advanceCount > 0 && advanceCount % circles != 0) {
            throw new ServiceException("{}总晋级数[{}]无法按实际[{}]圈均分,请调整晋级名额或圈数",
                modeLabel, advanceCount, circles);
        }
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : matches) {
            String zone = zoneOf(m);
            if (ctx.containsKey(zone)) {
                continue;
            }
            int quota = ordinal >= plannedCircles ? 0
                : explicitQuota && ordinal < perCircleCfg.size()
                    ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
            ctx.put(zone, new CircleQuota(ordinal, quota, acc));
            acc += quota;
            ordinal++;
        }
        return ctx;
    }
}
