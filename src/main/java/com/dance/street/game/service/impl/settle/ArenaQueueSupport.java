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

    /** 临时弃权标记前缀(存于 remark,格式 ARENA_SKIP:&lt;时间戳&gt;;可多个,取最后一次) */
    private static final String ARENA_SKIP_PREFIX = "ARENA_SKIP:";

    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TStageMapper stageMapper;

    /**
     * 擂台赛轮转队列:初始 = 签到顺序(seedRank),回放已结算对决重排。
     * 每场对决:胜者留在队首,败者排到队尾,其余保持相对顺序;
     * 平局时擂主(slot1)与挑战者(slot2)均排到队尾(保持原相对顺序)。
     */
    public List<Long> computeArenaQueue(Long stageId) {
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            // 弃权选手不参与排队/对阵/排名
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        // 临时弃权标记:有标记的选手固定排在队尾(按标记时间),避免被"胜者守擂"重放顶回队首
        List<TCompetitor> normal = new ArrayList<>();
        List<TCompetitor> skipped = new ArrayList<>();
        for (TCompetitor c : comps) {
            if (arenaSkipSeq(c) >= 0) {
                skipped.add(c);
            } else {
                normal.add(c);
            }
        }
        skipped.sort(Comparator.comparingLong(this::arenaSkipSeq).thenComparing(TCompetitor::getId));
        List<Long> queue = normal.stream().map(TCompetitor::getId).collect(Collectors.toCollection(ArrayList::new));

        List<TMatch> settled = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED)
            .orderByAsc(TMatch::getId));
        for (TMatch m : settled) {
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            // 平局:擂主(slot1)与挑战者(slot2)均移到队尾,其余保持相对顺序
            boolean isDraw = parts.stream().anyMatch(p -> p.getCompetitorId() != null
                && MatchOutcomeEnum.DRAW.getCode().equals(p.getOutcomeStatus()));
            if (isDraw) {
                Long defender = null;
                Long challenger = null;
                for (TMatchParticipant p : parts) {
                    if (p.getCompetitorId() == null) {
                        continue;
                    }
                    if (p.getDisplaySlotIndex() != null && p.getDisplaySlotIndex() == 1L) {
                        defender = p.getCompetitorId();
                    } else if (p.getDisplaySlotIndex() != null && p.getDisplaySlotIndex() == 2L) {
                        challenger = p.getCompetitorId();
                    }
                }
                if (defender == null || challenger == null
                    || !queue.contains(defender) || !queue.contains(challenger)) {
                    // 异常数据(如中途改判/重启残留)跳过该场,保持当前队列
                    continue;
                }
                List<Long> next = new ArrayList<>();
                for (Long cid : queue) {
                    if (!cid.equals(defender) && !cid.equals(challenger)) {
                        next.add(cid);
                    }
                }
                next.add(defender);
                next.add(challenger);
                queue = next;
                continue;
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
                // 异常数据(如中途改判/重启残留)跳过该场,保持当前队列
                continue;
            }
            List<Long> next = new ArrayList<>();
            next.add(winner);
            for (Long cid : queue) {
                if (!cid.equals(winner) && !cid.equals(loser)) {
                    next.add(cid);
                }
            }
            next.add(loser);
            queue = next;
        }
        for (TCompetitor c : skipped) {
            queue.add(c.getId());
        }
        return queue;
    }

    /** 擂台赛积分:统计本赛段全部场次中参赛者的胜场数(每胜一场 +1) */
    public Map<Long, Integer> arenaPoints(Long stageId) {
        Map<Long, Integer> wins = new HashMap<>();
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (matches.isEmpty()) {
            return wins;
        }
        // 平局双方各加1分:由赛段配置 drawBothScore 控制(默认关闭,只按胜场记分)
        TStage stage = stageMapper.selectById(stageId);
        RuleConfigHolder rc = stage != null ? RuleConfigParser.parse(stage.getRuleConfig()) : null;
        boolean drawBothScore = rc != null && Boolean.TRUE.equals(rc.getDrawBothScore());
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

    /** 读取临时弃权时间戳;无标记返回 -1 */
    public long arenaSkipSeq(TCompetitor c) {
        String r = c.getRemark();
        if (r == null || r.isBlank()) {
            return -1L;
        }
        int idx = r.lastIndexOf(ARENA_SKIP_PREFIX);
        if (idx < 0) {
            return -1L;
        }
        try {
            String rest = r.substring(idx + ARENA_SKIP_PREFIX.length());
            return Long.parseLong(rest.split(";")[0].trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 追加一次临时弃权标记 */
    public String appendArenaSkipMark(String remark) {
        String mark = ARENA_SKIP_PREFIX + System.currentTimeMillis();
        return (remark == null || remark.isBlank()) ? mark : remark + ";" + mark;
    }
}
