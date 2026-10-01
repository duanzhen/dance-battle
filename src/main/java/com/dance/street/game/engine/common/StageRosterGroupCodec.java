package com.dance.street.game.engine.common;

import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 名单来源组旧 JSON 格式的编解码。
 *
 * <p><b>来源组已表化</b>:运行时事实源是 {@code t_stage_roster_group}(赛段间依赖的边+取人规则),
 * 详见 {@code TStageRosterServiceImpl}。本类现在只服务一件事——
 * 启动时把老库里 {@code t_stage.roster_config_json} 的历史规则一次性搬进边表
 * (见 {@code DatabaseSchemaInitializer#migrateRosterGroups}),新代码不要再用它读写规则。</p>
 *
 * @author duane
 */
public final class StageRosterGroupCodec {

    private static final ObjectMapper MAPPER = SnowflakeJson.mapper();

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
                // 旧 JSON 里的 priority 已废弃(表化后由 sort_order 决定取人顺序):
                // 这里读到也不再用,搬迁时按数组顺序写入 sort_order。
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

}
