package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.RankCalculator;
import com.dance.street.game.engine.scoring.ScoringEngine;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.ITScoredMatchService;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排名赛结算:聚合全部裁判×维度打分,计算总分排名,晋级线内正常晋级;
 * 晋级线上同分并列导致名额超限时,同分者保持 PENDING,由导播台在中间态手动指定晋级者。
 *
 * <p>排名赛不分圈:整段只有一场(全员挂在该场),按赛段晋级名额取前 N 名。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankStageSettler implements StageSettler {

    private final ScoringEngine scoringEngine;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final CompetitorOutcomeWriter outcomeWriter;
    private final SettlementSupport settlementSupport;
    private final ITScoredMatchService scoredMatchService;

    @Override
    public String stageMode() {
        return StageModeEnum.RANK.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        // 「还没判完」属于"还没准备好",与"场次未结算"同类:返回 pending 让导播台按提示补齐,
        // 而不是抛异常当失败处理。必须在结算前统一扫一遍,避免像异常那样中途中断、只结算了一半场次。
        //
        // 「判完」的口径与海选一致:每名选手都要被本场应到的每一名裁判打过。此前这里退化成
        // 「任一裁判打过即可」——3 名裁判只到 1 名时照样结算,名次按 1/3 的分数算出来。
        List<String> unjudged = scoredMatchService.unjudgedNames(stage.getId());
        if (!unjudged.isEmpty()) {
            return StageSettleOutcome.pending("本段仍有 " + unjudged.size() + " 名选手未打分或未打满本场裁判数"
                + "(未标记退赛): " + String.join("、", unjudged) + ",请先完成打分或标记退赛后再结算");
        }
        settleRankStage(stage);
        long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (unfinished > 0) {
            return StageSettleOutcome.pending("赛段仍有 " + unfinished + " 场未结算,等待全部结算后才能结束");
        }
        return StageSettleOutcome.completed();
    }

    private void settleRankStage(TStage stage) {
        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        // 排名赛不分圈:整段共用一个名额池,晋级人数取赛段晋级名额(与海选名单取人同一口径),
        // 已晋级者按人去重(支持重复结算幂等)。
        int advanceQuota = StageFlowSupport.readStageAdvanceCount(stage);
        int alreadyAdvanced = countRankAdvanced(matches);
        // 剩余名额与"下一个要分配的最终名次"随场次依次推进
        int[] remainingSeats = {Math.max(0, advanceQuota - alreadyAdvanced)};
        int[] rankCursor = {alreadyAdvanced};

        // 批量预取参赛方/轮次/打分/参赛单位:此前每圈各发 4 条查询(K 圈 ≈ 4K 条),现在固定 4 条
        List<Long> allMatchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TMatchParticipant>> partsByMatch = allMatchIds.isEmpty() ? Map.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, allMatchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId))
                .stream()
                .filter(p -> p.getMatchId() != null)
                .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        List<TMatchRound> allRounds = allMatchIds.isEmpty() ? List.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, allMatchIds)
                .select(TMatchRound::getId, TMatchRound::getMatchId));
        Map<Long, List<Long>> roundIdsByMatch = allRounds.stream()
            .filter(r -> r.getMatchId() != null && r.getId() != null)
            .collect(Collectors.groupingBy(TMatchRound::getMatchId,
                Collectors.mapping(TMatchRound::getId, Collectors.toList())));
        List<Long> allRoundIds = allRounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TRoundScore>> scoresByRound = allRoundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, allRoundIds))
                .stream()
                .filter(s -> s.getRoundId() != null)
                .collect(Collectors.groupingBy(TRoundScore::getRoundId));
        List<Long> allCompIds = partsByMatch.values().stream()
            .flatMap(List::stream)
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> allCompById = settlementSupport.competitorMap(allCompIds);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            settleRankMatch(match, rc, remainingSeats, rankCursor,
                partsByMatch.getOrDefault(match.getId(), List.of()),
                roundIdsByMatch.getOrDefault(match.getId(), List.of()),
                scoresByRound, allCompById);
        }
    }

    /**
     * 统计排名赛已晋级人数,重复结算时用它计算剩余名额。
     *
     * <p>与海选同一口径:<b>按人去重</b>。晋级结果会同步到该选手在本赛段的全部参赛行
     * (见 {@code markManualAdvanceResult}),按行计数会把同一名晋级者重复算进名额。</p>
     */
    private int countRankAdvanced(List<TMatch> matches) {
        List<Long> matchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        if (matchIds.isEmpty()) {
            return 0;
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        Set<Long> advancers = new HashSet<>();
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() == null) {
                continue;
            }
            advancers.add(p.getCompetitorId());
        }
        return advancers.size();
    }

    /**
     * 结算单场排名赛:跨本场全部轮次聚合多裁判×多维度分,用 RANKING 策略算总分排名,
     * 按剩余名额晋级;晋级线同分并列时保持 PENDING。
     *
     * @param remainingSeats 剩余晋级名额(单元素数组,跨场次依次扣减)
     * @param rankCursor     下一个要分配的最终名次(单元素数组,跨场次依次推进)
     */
    private void settleRankMatch(TMatch match, RuleConfigHolder rc, int[] remainingSeats, int[] rankCursor,
                                 List<TMatchParticipant> parts, List<Long> roundIds,
                                 Map<Long, List<TRoundScore>> scoresByRound,
                                 Map<Long, TCompetitor> compMap) {
        if (parts.isEmpty()) {
            settlementSupport.markMatchSettled(match);
            return;
        }
        List<Long> partIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();
        // 退赛选手不参与排名、不占用晋级名额(保持 WITHDRAWN)
        List<Long> competitorIds = partIds.stream()
            .filter(cid -> {
                TCompetitor c = compMap.get(cid);
                return c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
            })
            .toList();
        if (competitorIds.isEmpty()) {
            settlementSupport.markMatchSettled(match);
            return;
        }
        // 未打分守卫:从未被任何裁判打分的选手不允许随结算"0 分自动晋级"
        Map<Long, Long> scoreCountByCompetitor = roundIds.isEmpty() ? Map.of()
            : roundIds.stream()
                .flatMap(id -> scoresByRound.getOrDefault(id, List.of()).stream())
                .filter(rs -> rs.getCompetitorId() != null)
                .collect(Collectors.groupingBy(TRoundScore::getCompetitorId, Collectors.counting()));
        List<String> unjudged = competitorIds.stream()
            .map(compMap::get).filter(Objects::nonNull)
            .filter(c -> !scoreCountByCompetitor.containsKey(c.getId()))
            .map(TCompetitor::getName)
            .toList();
        if (!unjudged.isEmpty()) {
            // 兜底:{@link #settle} 已前置拦截该条件并返回 pending,
            // 这里保留守卫以防将来有人绕过 settle 直接调用本方法。
            throw new ServiceException("本段仍有 {} 名选手未打分(未标记退赛): {},请先完成打分或标记退赛后再结算",
                unjudged.size(), unjudged);
        }

        // 分数分布在各自轮次,跨本场全部轮次汇总
        List<TRoundScore> allScores = roundIds.isEmpty() ? List.of()
            : roundIds.stream()
                .flatMap(id -> scoresByRound.getOrDefault(id, List.of()).stream())
                .toList();

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.RANKING)
            .scoringConfig(rc != null ? rc.getScoring() : null)
            .competitorIds(competitorIds)
            .rawScores(allScores)
            .build();
        List<MatchScoreResult> results = scoringEngine.compute(input);

        // 回写 participant 总分/排名(胜负状态统一在下面按晋级结果标)
        for (MatchScoreResult r : results) {
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setScoreValue(r.getScoreValue());
            pUpd.setRankInMatch(r.getRankInMatch() == null ? null : r.getRankInMatch().longValue());
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, match.getId())
                .eq(TMatchParticipant::getCompetitorId, r.getCompetitorId()));
        }

        int remaining = remainingSeats[0];
        int rankBase = rankCursor[0];

        Map<Long, BigDecimal> scores = new HashMap<>();
        for (MatchScoreResult r : results) {
            if (r.getCompetitorId() != null) {
                scores.put(r.getCompetitorId(), r.getScoreValue() != null ? r.getScoreValue() : BigDecimal.ZERO);
            }
        }
        List<Long> sortedCids = new ArrayList<>(scores.keySet());
        sortedCids.sort((a, b) -> scores.getOrDefault(b, BigDecimal.ZERO)
            .compareTo(scores.getOrDefault(a, BigDecimal.ZERO)));
        Map<Long, Integer> ranks = RankCalculator.rank(scores);

        // 剩余名额已满:本场(重复结算/异常数据)所有人淘汰
        if (remaining <= 0) {
            for (int i = 0; i < sortedCids.size(); i++) {
                markRankResult(sortedCids.get(i), OutcomeStatusEnum.ELIMINATED.getCode(),
                    (long) (rankBase + i + 1), ranks, match);
            }
            rankCursor[0] = rankBase + sortedCids.size();
            settlementSupport.markMatchSettled(match);
            log.info("排名赛场次[{}]结算:晋级名额已满,{}名选手淘汰", match.getId(), sortedCids.size());
            return;
        }

        // 检测晋级线上的同分并列:晋级名额被同分横跨时,同分者保持 PENDING 由导播台定夺
        if (remaining < sortedCids.size()) {
            BigDecimal cutoffScore = scores.get(sortedCids.get(remaining - 1));
            List<Long> tiedAtCutoff = new ArrayList<>();
            for (Long cid : sortedCids) {
                if (scores.get(cid).compareTo(cutoffScore) == 0) {
                    tiedAtCutoff.add(cid);
                }
            }
            int lastTiedPos = sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1));
            if (tiedAtCutoff.size() > 1 && remaining <= lastTiedPos) {
                int firstTiedPos = sortedCids.indexOf(tiedAtCutoff.get(0));
                // 明确晋级者:同分群之前
                for (int i = 0; i < firstTiedPos; i++) {
                    Long cid = sortedCids.get(i);
                    markRankResult(cid, OutcomeStatusEnum.ADVANCE.getCode(),
                        (long) (rankBase + i + 1), ranks, match);
                }
                // 明确淘汰者:同分群之后
                for (int i = lastTiedPos + 1; i < sortedCids.size(); i++) {
                    Long cid = sortedCids.get(i);
                    markRankResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (rankBase + i + 1), ranks, match);
                }
                remainingSeats[0] = remaining - firstTiedPos;
                rankCursor[0] = rankBase + sortedCids.size();
                // 同分者保持 PENDING,由导播台在中间态调整中手动决定晋级
                log.info("排名赛场次[{}]晋级线出现{}名同分并列,保持待定等待导播台调整", match.getId(), tiedAtCutoff.size());
                settlementSupport.markMatchSettled(match);
                return;
            }
        }

        // 无同分问题:正常结算
        for (int i = 0; i < sortedCids.size(); i++) {
            Long cid = sortedCids.get(i);
            boolean advance = i < remaining;
            markRankResult(cid, advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                (long) (rankBase + i + 1), ranks, match);
        }
        remainingSeats[0] = remaining - Math.min(remaining, sortedCids.size());
        rankCursor[0] = rankBase + sortedCids.size();
        settlementSupport.markMatchSettled(match);
        log.info("排名赛场次[{}]已结算,共{}名选手,晋级{}名", match.getId(), sortedCids.size(),
            Math.min(remaining, sortedCids.size()));
    }

    /** 回写排名赛参赛者结果:competitor 赛段级状态 + participant 场次级状态 */
    private void markRankResult(Long cid, String outcome, Long finalRank,
                                Map<Long, Integer> ranks, TMatch match) {
        outcomeWriter.writeResult(cid, outcome, finalRank);

        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setRankInMatch(ranks.get(cid) != null ? ranks.get(cid).longValue() : null);
        pUpd.setOutcomeStatus(outcome);
        participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, cid));
    }
}
