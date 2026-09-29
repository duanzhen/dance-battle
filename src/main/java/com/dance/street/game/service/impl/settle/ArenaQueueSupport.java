package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 擂台赛轮转队列与积分口径。
 *
 * <p>「下一场谁上」「现在谁在擂台上」「胜场积分多少」既被导播台总览查询用到,
 * 也被赛段结算用到;放在一处保证查询与结算看到的是同一个队列。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class ArenaQueueSupport {

    /** 临时弃权标记前缀(存于 remark,格式 ARENA_SKIP:&lt;时间戳&gt;[:&lt;标记时场次数&gt;];可多个,取最后一次) */
    private static final String ARENA_SKIP_PREFIX = "ARENA_SKIP:";

    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TStageMapper stageMapper;

    /** 临时弃权标记:timestamp=标记时间,boundary=标记发生时本赛段已有的场次数(含进行中的那一场) */
    public record ArenaSkipMark(long timestamp, int boundary) {
    }

    /** 队列时间线上的临时弃权事件:回放完 applyAfter 场之后把该选手挪到队尾(只生效一次) */
    private record SkipEvent(Long competitorId, int applyAfter, long timestamp) {
    }

    /**
     * 擂台赛轮转队列:初始 = 签到顺序(seedRank),回放已结算对决重排。
     * 每场对决:胜者留在队首,败者排到队尾,其余保持相对顺序;
     * 平局时擂主(slot1)与挑战者(slot2)均排到队尾(保持原相对顺序)。
     *
     * <p>临时弃权是队列时间线上的一次性事件:回放到"标记发生时那一场"结束后,把该选手挪到队尾,
     * 之后他与别人一样按"胜者守擂、败者队尾"继续轮转——不是永久降级。
     * (旧实现把弃权者从队列里摘掉、每次重算再追加到末尾,而标记永不过期,
     * 结果是被临时弃权的人永远排在队尾、再也没有上场机会。)</p>
     */
    public List<Long> computeArenaQueue(Long stageId) {
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            // 弃权选手不参与排队/对阵/排名
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        // 弃权者留在队列里(只是被挪到队尾),否则他参与过的场次在回放时会因"人不在队列"被整场跳过,
        // 其他选手的位置也会跟着错乱
        List<Long> queue = comps.stream().map(TCompetitor::getId)
            .collect(Collectors.toCollection(ArrayList::new));
        if (queue.isEmpty()) {
            return queue;
        }

        List<TMatch> settled = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED)
            .orderByAsc(TMatch::getId));
        // 一次性取回全部已结算对决的参赛方:此前每场查一次,
        // 50 场擂台就是每次总览 50 条 SQL(总览/结算/弃权补位都在调本方法)
        Map<Long, List<TMatchParticipant>> partsByMatch = new HashMap<>();
        if (!settled.isEmpty()) {
            List<Long> settledIds = settled.stream().map(TMatch::getId).toList();
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, settledIds)
                    .orderByAsc(TMatchParticipant::getDisplaySlotIndex))
                .forEach(p -> partsByMatch
                    .computeIfAbsent(p.getMatchId(), k -> new ArrayList<>())
                    .add(p));
        }
        // 临时弃权事件(一次性):回放完第 applyAfter 场之后把该选手挪到队尾。
        // 标记里记的是"标记发生时的场次数",下标因此固定;若场次被删除/重置导致这个下标
        // 永远到不了,则退回时间线开头,避免选手被永久钉在队尾。
        long totalMatches = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        List<SkipEvent> skips = new ArrayList<>();
        for (TCompetitor c : comps) {
            ArenaSkipMark mark = arenaSkipMark(c);
            if (mark == null) {
                continue;
            }
            int boundary = mark.boundary() > totalMatches ? 0 : mark.boundary();
            skips.add(new SkipEvent(c.getId(), Math.min(boundary, settled.size()), mark.timestamp()));
        }
        skips.sort(Comparator.comparingInt(SkipEvent::applyAfter).thenComparingLong(SkipEvent::timestamp));

        // 回放与弃权事件坐在同一条时间线上,按发生顺序依次作用
        int cursor = 0;
        for (int i = 0; i <= settled.size(); i++) {
            if (i > 0) {
                replaySettledMatch(queue, partsByMatch.getOrDefault(settled.get(i - 1).getId(), List.of()));
            }
            while (cursor < skips.size() && skips.get(cursor).applyAfter() <= i) {
                moveToTail(queue, skips.get(cursor).competitorId());
                cursor++;
            }
        }
        return queue;
    }

    /**
     * 回放一场已结算对决:胜者守擂(队首)、败者队尾;平局时擂主与挑战者均到队尾,其余保持相对顺序。
     * 数据异常(选手不在队列,如中途改判/重启残留)时跳过该场,保持当前队列。
     */
    private void replaySettledMatch(List<Long> queue, List<TMatchParticipant> parts) {
        boolean isDraw = parts.stream().anyMatch(p -> p.getCompetitorId() != null
            && MatchOutcomeEnum.DRAW.getCode().equals(p.getOutcomeStatus()));
        if (isDraw) {
            Long defender = null;
            Long challenger = null;
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                if (Long.valueOf(1L).equals(p.getDisplaySlotIndex())) {
                    defender = p.getCompetitorId();
                } else if (Long.valueOf(2L).equals(p.getDisplaySlotIndex())) {
                    challenger = p.getCompetitorId();
                }
            }
            if (defender == null || challenger == null
                || !queue.contains(defender) || !queue.contains(challenger)) {
                return;
            }
            moveToTail(queue, defender);
            moveToTail(queue, challenger);
            return;
        }
        Long winner = null;
        Long loser = null;
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() == null) {
                continue;
            }
            if (MatchOutcomeEnum.WIN.getCode().equals(p.getOutcomeStatus())) {
                winner = p.getCompetitorId();
            } else if (MatchOutcomeEnum.LOSS.getCode().equals(p.getOutcomeStatus())) {
                loser = p.getCompetitorId();
            }
        }
        if (winner == null || loser == null || !queue.contains(winner) || !queue.contains(loser)) {
            return;
        }
        moveToHead(queue, winner);
        moveToTail(queue, loser);
    }

    private void moveToHead(List<Long> queue, Long competitorId) {
        if (queue.remove(competitorId)) {
            queue.add(0, competitorId);
        }
    }

    private void moveToTail(List<Long> queue, Long competitorId) {
        if (queue.remove(competitorId)) {
            queue.add(competitorId);
        }
    }

    /** 擂台赛积分:统计本赛段全部场次中参赛者的胜场数(每胜一场 +1) */
    public Map<Long, Integer> arenaPoints(Long stageId) {
        Map<Long, Integer> wins = new HashMap<>();
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (matches.isEmpty()) {
            return wins;
        }
        // 平局计分:默认「双方各 +1 分」(drawBothScore 缺省即开启);
        // 赛段配置显式设 false 时回到"平局都不加分,只按胜场记分"
        TStage stage = stageMapper.selectById(stageId);
        RuleConfigHolder rc = stage != null ? RuleConfigParser.parse(stage.getRuleConfig()) : null;
        boolean drawBothScore = rc == null || !Boolean.FALSE.equals(rc.getDrawBothScore());
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.WIN.getCode()));
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                wins.merge(p.getCompetitorId(), 1, Integer::sum);
            }
        }
        if (drawBothScore) {
            List<TMatchParticipant> draws = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .eq(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.DRAW.getCode()));
            for (TMatchParticipant p : draws) {
                if (p.getCompetitorId() != null) {
                    wins.merge(p.getCompetitorId(), 1, Integer::sum);
                }
            }
        }
        return wins;
    }

    /**
     * 读取最后一次临时弃权标记;无标记/格式非法返回 null。
     * 历史标记没有场次数段,按时间线开头(下标 0)处理——已经"永久钉死"的旧数据也能重新回到轮转。
     */
    public ArenaSkipMark arenaSkipMark(TCompetitor c) {
        String r = c.getRemark();
        if (r == null || r.isBlank()) {
            return null;
        }
        int idx = r.lastIndexOf(ARENA_SKIP_PREFIX);
        if (idx < 0) {
            return null;
        }
        try {
            String[] seg = r.substring(idx + ARENA_SKIP_PREFIX.length()).split(";")[0].trim().split(":");
            long ts = Long.parseLong(seg[0].trim());
            int boundary = seg.length > 1 ? Integer.parseInt(seg[1].trim()) : 0;
            return new ArenaSkipMark(ts, Math.max(0, boundary));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 追加一次临时弃权标记;boundary = 标记发生时本赛段已有的场次数(含进行中的那一场) */
    public String appendArenaSkipMark(String remark, long boundary) {
        String mark = ARENA_SKIP_PREFIX + System.currentTimeMillis() + ":" + Math.max(0L, boundary);
        return (remark == null || remark.isBlank()) ? mark : remark + ";" + mark;
    }
}
