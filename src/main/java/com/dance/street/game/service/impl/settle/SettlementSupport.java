package com.dance.street.game.service.impl.settle;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.MatchStateWriter;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 赛段结算的共享内核:被多种结算策略复用的纯口径与落库动作。
 *
 * <p>这些动作此前是 {@code TStageLifecycleServiceImpl} 的私有方法,
 * 结算逻辑外移后由多个策略共用,因此单独提出来——否则每个策略各写一份,
 * 口径会立刻分叉。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class SettlementSupport {

    /** 加赛场次标记前缀(存于 t_match.remark),用于识别二海/三海… */
    public static final String TIEBREAKER_PREFIX = "同分加赛";

    private final TCompetitorMapper competitorMapper;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    /** 场次状态推进(场次 + 轮次成套写) */
    private final MatchStateWriter matchStateWriter;

    /** 场次与轮次置为已结算并通知裁判端/赛事事件 */
    public void markMatchSettled(TMatch match) {
        matchStateWriter.setStatus(match.getId(), StageConstants.MATCH_SETTLED);
        refereeSseNotifier.notifyMatch(match.getStageId(), match.getId(), "match");
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), match.getId(), "match");
    }

    /**
     * 场次是否为同分加赛(二海/三海…)。
     *
     * <p>口径:优先看 {@code t_match.match_type}(显式字段);历史数据该列为 null,
     * 退回 remark 前缀「同分加赛」识别——老库不需要迁移即可继续工作。</p>
     */
    public static boolean isTiebreaker(TMatch match) {
        if (match == null) {
            return false;
        }
        if (StageConstants.MATCH_TYPE_TIEBREAKER.equals(match.getMatchType())) {
            return true;
        }
        if (match.getMatchType() != null) {
            return false;
        }
        return StringUtils.isNotBlank(match.getRemark()) && match.getRemark().startsWith(TIEBREAKER_PREFIX);
    }

    /** 参赛号码转数值用于排序:非数字号码排最后 */
    public static int parseCompetitorNumber(String number) {
        if (number == null || number.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 按 ID 批量取参赛方,返回 id -> 参赛方(空集合返回空 Map) */
    public Map<Long, TCompetitor> competitorMap(Collection<Long> competitorIds) {
        if (competitorIds == null || competitorIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = competitorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return competitorMapper.selectByIds(ids).stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
    }
}
