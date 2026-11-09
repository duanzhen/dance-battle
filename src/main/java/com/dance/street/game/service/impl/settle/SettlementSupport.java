package com.dance.street.game.service.impl.settle;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.MatchStateWriter;
import lombok.RequiredArgsConstructor;
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

    /** 场次是否为同分加赛(二海/三海…):以 {@code t_match.match_type} 为唯一口径 */
    public static boolean isTiebreaker(TMatch match) {
        return match != null && StageConstants.MATCH_TYPE_TIEBREAKER.equals(match.getMatchType());
    }

    /** 加赛深度:场次名中「加赛」的出现次数,最小为 1(二海)。 */
    public static int tiebreakerDepth(TMatch match) {
        String name = match == null || match.getName() == null ? "" : match.getName();
        int depth = 0;
        for (int i = name.indexOf("加赛"); i >= 0; i = name.indexOf("加赛", i + 2)) {
            depth++;
        }
        return Math.max(1, depth);
    }

    /** 加赛轮次名:1=二海,2=三海,3=四海,4=五海,更深层使用「加赛N」。 */
    public static String tiebreakerRoundName(int depth) {
        return switch (Math.max(1, depth)) {
            case 1 -> "二海";
            case 2 -> "三海";
            case 3 -> "四海";
            case 4 -> "五海";
            default -> "加赛" + Math.max(1, depth);
        };
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
