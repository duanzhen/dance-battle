package com.dance.street.game.engine.common;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 名单来源组(存于 t_stage.roster_config_json)编解码。
 *
 * <p>多来源以 groups 为唯一事实源,规则即数据,无独立实体/状态机。</p>
 *
 * @author duane
 */
public final class StageRosterGroupCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StageRosterGroupCodec() {
    }

    /** 解析名单行的来源组列表;config 缺失/损坏时返回空列表由调用方兜底 */
    public static List<TStageRosterGroupBo> parse(String configJson) {
        List<TStageRosterGroupBo> out = new ArrayList<>();
        if (configJson == null || configJson.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(configJson);
            JsonNode groups = root.get("groups");
            if (groups == null || !groups.isArray()) {
                return out;
            }
            for (JsonNode n : groups) {
                TStageRosterGroupBo g = new TStageRosterGroupBo();
                if (n.hasNonNull("sourceStageId")) {
                    g.setSourceStageId(n.get("sourceStageId").asLong());
                }
                if (n.hasNonNull("resultFilter")) {
                    g.setResultFilter(n.get("resultFilter").asText());
                }
                if (n.hasNonNull("zone")) {
                    g.setZone(n.get("zone").asText());
                }
                if (n.hasNonNull("rankStart")) {
                    g.setRankStart(n.get("rankStart").asInt());
                }
                if (n.hasNonNull("rankEnd")) {
                    g.setRankEnd(n.get("rankEnd").asInt());
                }
                if (n.hasNonNull("rankByZone")) {
                    g.setRankByZone(n.get("rankByZone").asBoolean());
                }
                if (n.hasNonNull("round")) {
                    g.setRound(n.get("round").asInt());
                }
                if (n.hasNonNull("scoreMin")) {
                    g.setScoreMin(n.get("scoreMin").decimalValue());
                }
                if (n.hasNonNull("scoreMax")) {
                    g.setScoreMax(n.get("scoreMax").decimalValue());
                }
                if (n.hasNonNull("fillMode")) {
                    g.setFillMode(n.get("fillMode").asText());
                }
                if (n.hasNonNull("quota")) {
                    g.setQuota(n.get("quota").asInt());
                }
                if (n.hasNonNull("priority")) {
                    g.setPriority(n.get("priority").asInt());
                }
                if (n.hasNonNull("orderBy")) {
                    g.setOrderBy(n.get("orderBy").asText());
                }
                out.add(g);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("名单行 groups 配置损坏: " + e.getMessage(), e);
        }
        return out;
    }

    /** 序列化来源组列表为 config_json */
    public static String write(List<TStageRosterGroupBo> groups) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groups", groups);
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("来源组规则序列化失败: " + e.getMessage(), e);
        }
    }
}
