package com.dance.street.game.service.impl;

import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

/**
 * 大屏/导播台「当前上场选手」标记的跨实例存储。
 *
 * <p>这是个纯现场标记(不参与结算、丢了也不影响赛果),所以不进数据库:
 * 放进 {@link RedisUtils} 这一层即可——distributed 多实例部署时导播台在 A 实例
 * 标记、大屏从 B 实例读是同一个值;Redis 不可用(standalone / native)时
 * RedisUtils 自动降级为进程内缓存,语义与「单机就跑一台」完全一致。</p>
 *
 * <p>值按字符串存:与 {@code TournamentSseEmitterManager} 的「大屏当前场景」同口径,
 * 既避开不同 Redisson codec 下数字类型漂移,也顺应本项目雪花 ID 一律当字符串的约定。</p>
 *
 * <p>无 TTL:标记由导播显式设置/清除,键数量与场次数同量级(一场一个键),
 * 不需要额外过期策略;若将来要清理,可考虑在赛段重置/删除时按前缀回收。</p>
 *
 * @author duane
 */
@Component
public class MatchCurrentCompetitorStore {

    /** 缓存键前缀(供测试与运维排查引用) */
    public static final String KEY_PREFIX = "game:match:current-competitor:";

    /**
     * 标记当前上场选手;{@code competitorId} 传 null 表示清除标记。
     */
    public void mark(Long matchId, Long competitorId) {
        if (matchId == null) {
            return;
        }
        String key = KEY_PREFIX + matchId;
        if (competitorId == null) {
            RedisUtils.deleteObject(key);
        } else {
            RedisUtils.setCacheObject(key, String.valueOf(competitorId));
        }
    }

    /** 查询当前上场选手;未标记返回 null */
    public Long current(Long matchId) {
        if (matchId == null) {
            return null;
        }
        String raw = RedisUtils.getCacheObject(KEY_PREFIX + matchId);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
