package com.dance.street.game.service.impl.settle;

import com.dance.street.game.engine.common.SnowflakeJson;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 海选加赛「导播手动指定晋级」结果的编解码。
 *
 * <p>存在 {@code t_match.result_json} 上,内容是有序的参赛方ID数组——
 * 顺序即导播指定的晋级先后(落库时按号码牌升序规范化)。仅 <b>手动指定</b> 模式的加赛场次使用。</p>
 */
public final class AuditionTiebreakResult {

    private static final ObjectMapper MAPPER = SnowflakeJson.mapper();

    private AuditionTiebreakResult() {
    }

    /** 写入:有序参赛方ID数组;空集合写 null(视为未指定)。 */
    public static String write(List<Long> competitorIds) {
        if (competitorIds == null || competitorIds.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(competitorIds);
        } catch (Exception e) {
            throw new IllegalStateException("加赛指定晋级序列化失败: " + e.getMessage(), e);
        }
    }

    /** 读取:未指定/解析失败返回空列表。 */
    public static List<Long> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Long[] arr = MAPPER.readValue(json, Long[].class);
            return arr == null ? List.of() : new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            return List.of();
        }
    }
}
