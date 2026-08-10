package com.dance.street.game.engine.common;

import tools.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;

import java.util.Map;

/**
 * rule_config / promotion_rule JSON 解析工具。
 *
 * <p>使用独立的 Jackson ObjectMapper(不依赖 Spring 上下文 / ruoyi-common-json 的 JsonUtils),
 * 保证 engine 包可独立单元测试。POJO 上的 {@code @JsonIgnoreProperties(ignoreUnknown=true)}
 * 保证对前端附加的未知字段向前兼容。</p>
 *
 * <p>rule_config 是后端内部配置 JSON,不需要全局 ObjectMapper 的定制(如 Long→String 防前端精度丢失),
 * 标准 Jackson 行为已足够。</p>
 */
public final class RuleConfigParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleConfigParser() {
    }

    /** 解析 TStage.rule_config;空串返回 null;解析失败抛 ServiceException */
    public static RuleConfigHolder parse(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, RuleConfigHolder.class);
        } catch (Exception e) {
            throw new ServiceException("rule_config 解析失败: {}", e.getMessage());
        }
    }

    /** 序列化 RuleConfigHolder;null 返回 null */
    public static String toJson(RuleConfigHolder holder) {
        if (holder == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(holder);
        } catch (Exception e) {
            throw new ServiceException("rule_config 序列化失败: {}", e.getMessage());
        }
    }

    /** 序列化 TMatch.promotion_rule:Map<rankPosition, PromotionTarget>;空返回 null */
    public static String toJsonPromotionRule(Map<String, PromotionTarget> rule) {
        if (rule == null || rule.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(rule);
        } catch (Exception e) {
            throw new ServiceException("promotion_rule 序列化失败: {}", e.getMessage());
        }
    }

    /** 解析 TMatch.promotion_rule;空返回空 Map */
    public static Map<String, PromotionTarget> parsePromotionRule(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, PromotionTarget> map = MAPPER.readValue(json,
                new tools.jackson.core.type.TypeReference<Map<String, PromotionTarget>>() {});
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            throw new ServiceException("promotion_rule 解析失败: {}", e.getMessage());
        }
    }
}
