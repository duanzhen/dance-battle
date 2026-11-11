package com.dance.street.game.service.impl;

import com.dance.street.game.domain.vo.WidgetScoreDetailTargetVo;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

/**
 * 大屏「评分详情」组件当前推送目标的跨实例存储。
 *
 * <p>这是纯现场标记(不参与结算、丢了也不影响赛果),所以不进数据库,也不写入
 * {@code t_vis_widget.dataConfig}:放进 {@link RedisUtils} 即可——distributed 多实例
 * 时导播台在 A 实例推送、大屏从 B 实例读是同一个值;Redis 不可用(standalone / native)
 * 时 RedisUtils 自动降级为进程内缓存。</p>
 *
 * <p>键按组件 ID 隔离(一块大屏可放多个评分详情,各自独立接收推送)。
 * 值按 JSON 字符串存:{@code {"matchId":"...","competitorId":"..."}}——与
 * {@link MatchCurrentCompetitorStore} 同口径,既避开不同 Redisson codec 下类型漂移,
 * 也顺应本项目雪花 ID 一律当字符串的约定。</p>
 *
 * <p>无 TTL:标记由导播显式设置/清除,键数量与评分详情组件数同量级,
 * 不需要额外过期策略。</p>
 *
 * @author duane
 */
@Component
public class WidgetScoreDetailStore {

    /** 缓存键前缀(供测试与运维排查引用) */
    public static final String KEY_PREFIX = "game:widget:score-detail:";

    /** 可视化组件类型常量 */
    public static final String WIDGET_TYPE = "SCORE_DETAIL";

    /**
     * 将指定选手的评分详情推到某个组件;{@code matchId} 或 {@code competitorId} 任一为空表示清除。
     */
    public void mark(Long widgetId, Long matchId, Long competitorId) {
        if (widgetId == null) {
            return;
        }
        String key = KEY_PREFIX + widgetId;
        if (matchId == null || competitorId == null) {
            RedisUtils.deleteObject(key);
            return;
        }
        // 显式写成字符串,避免 codec / 数字精度问题
        String json = "{\"matchId\":\"" + matchId + "\",\"competitorId\":\"" + competitorId + "\"}";
        RedisUtils.setCacheObject(key, json);
    }

    /** 查询组件当前推送目标;未推送或解析失败返回 null */
    public WidgetScoreDetailTargetVo current(Long widgetId) {
        if (widgetId == null) {
            return null;
        }
        String raw = RedisUtils.getCacheObject(KEY_PREFIX + widgetId);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            WidgetScoreDetailTargetVo vo = JsonUtils.parseObject(raw.trim(), WidgetScoreDetailTargetVo.class);
            if (vo == null || vo.getMatchId() == null || vo.getCompetitorId() == null) {
                return null;
            }
            return vo;
        } catch (Exception e) {
            return null;
        }
    }
}
