package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.impl.CompetitorOutcomeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 小组赛结算:按组(displayZone)累计参赛方胜负积分 → 组内排名 →
 * 前 advancePerGroup 名 outcomeStatus=ADVANCE 并写 finalRank,其余 ELIMINATED。
 *
 * <p>组内比赛同样是多裁判累计打分,因此先让 {@code scoredMatchService} 把
 * GAMING 场次结算掉,全部场次就绪后才排组内名次。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupStageSettler implements StageSettler {

    private final ITScoredMatchService scoredMatchService;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TCompetitorMapper competitorMapper;
    private final CompetitorOutcomeWriter outcomeWriter;

    @Override
    public String stageMode() {
        return StageModeEnum.GROUP.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        scoredMatchService.settleScoredMatches(stage.getId());
        long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (unfinished > 0) {
            return StageSettleOutcome.pending("赛段仍有 " + unfinished + " 场未结算,完成全部判罚后才能结束赛段");
        }
        settleGroupStage(stage);
        return StageSettleOutcome.completed();
    }

    /** 按组累计积分 → 组内排名 → 晋级/淘汰;晋级线同分并列者保持 PENDING 由导播台定夺 */
    private void settleGroupStage(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        GroupConfig gc = rc != null ? rc.getGroup() : null;
        int winP = (gc != null && gc.getWinPoints() != null) ? gc.getWinPoints() : 3;
        int drawP = (gc != null && gc.getDrawPoints() != null) ? gc.getDrawPoints() : 1;
        int lossP = (gc != null && gc.getLossPoints() != null) ? gc.getLossPoints() : 0;
        int advancePerGroup = (gc != null && gc.getAdvancePerGroup() != null) ? gc.getAdvancePerGroup() : 1;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
        if (matches.isEmpty()) {
            return;
        }

        // 按 displayZone(组)分组 matchId
        Map<String, List<Long>> groupMatchIds = matches.stream()
            .filter(m -> m.getDisplayZone() != null)
            .collect(Collectors.groupingBy(TMatch::getDisplayZone,
                Collectors.mapping(TMatch::getId, Collectors.toList())));

        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
        List<Long> compIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        for (Map.Entry<String, List<Long>> entry : groupMatchIds.entrySet()) {
            String zone = entry.getKey();
            List<Long> gMatchIds = entry.getValue();
            Set<Long> gSet = new HashSet<>(gMatchIds);
            // 组内参赛方(退赛除外)
            Set<Long> memberSet = new HashSet<>();
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() != null && gSet.contains(p.getMatchId())) {
                    TCompetitor c = compMap.get(p.getCompetitorId());
                    if (c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                        memberSet.add(p.getCompetitorId());
                    }
                }
            }
            List<Long> members = new ArrayList<>(memberSet);
            // competitorId -> 累计积分
            Map<Long, Integer> points = new HashMap<>();
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() == null || !gSet.contains(p.getMatchId())
                    || !members.contains(p.getCompetitorId())) {
                    continue;
                }
                String o = p.getOutcomeStatus();
                int pt;
                if (MatchOutcomeEnum.WIN.getCode().equals(o)) {
                    pt = winP;
                } else if (MatchOutcomeEnum.DRAW.getCode().equals(o)) {
                    pt = drawP;
                } else if (MatchOutcomeEnum.LOSS.getCode().equals(o)) {
                    pt = lossP;
                } else {
                    continue;
                }
                points.merge(p.getCompetitorId(), pt, Integer::sum);
            }

            // 组内按积分降序排名
            List<Long> scored = members.stream()
                .filter(points::containsKey)
                .sorted((a, b) -> {
                    int cmp = Integer.compare(points.get(b), points.get(a));
                    return cmp != 0 ? cmp : Long.compare(a, b);
                })
                .collect(Collectors.toList());
            int n = scored.size();
            int rankCursor = 1;
            // 晋级线同分并列:同分者保持 PENDING,由导播台在中间态用 adjustAdvancement 定夺
            Set<Long> pendingSet = new HashSet<>();
            if (advancePerGroup < n) {
                int cutoff = points.get(scored.get(advancePerGroup - 1));
                List<Long> tied = scored.stream()
                    .filter(c -> points.get(c) == cutoff).collect(Collectors.toList());
                int firstTied = scored.indexOf(tied.get(0));
                int lastTied = scored.indexOf(tied.get(tied.size() - 1));
                if (tied.size() > 1 && lastTied >= advancePerGroup) {
                    for (int i = 0; i < firstTied; i++) {
                        outcomeWriter.writeResult(scored.get(i), OutcomeStatusEnum.ADVANCE.getCode(), (long) (i + 1));
                    }
                    for (int i = lastTied + 1; i < n; i++) {
                        outcomeWriter.writeResult(scored.get(i), OutcomeStatusEnum.ELIMINATED.getCode(), (long) (i + 1));
                    }
                    pendingSet.addAll(tied);
                    rankCursor = n + 1;
                    log.info("组[{}]晋级线出现{}名同分并列,保持待定等待导播台调整", zone, tied.size());
                }
            }
            if (pendingSet.isEmpty()) {
                for (int i = 0; i < n; i++) {
                    boolean advance = i < advancePerGroup;
                    outcomeWriter.writeResult(scored.get(i),
                        advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (i + 1));
                }
                rankCursor = n + 1;
            }
            // 未出场/未获分者(退赛除外)标记淘汰,排名顺延
            for (Long cid : members) {
                if (!points.containsKey(cid)) {
                    outcomeWriter.writeResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(), (long) rankCursor++);
                }
            }
        }
    }
}
