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
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排名赛结算:按圈聚合全部裁判×维度打分,计算每圈总分排名,晋级线内正常晋级;
 * 晋级线上同分并列导致名额超限时,同分者保持 PENDING,由导播台在中间态手动指定晋级者。
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankStageSettler implements StageSettler {

    private final ScoringEngine scoringEngine = new ScoringEngine();
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final CompetitorOutcomeWriter outcomeWriter;
    private final SettlementSupport settlementSupport;

    @Override
    public String stageMode() {
        return StageModeEnum.RANK.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        // 「还有选手一条分都没打」属于"还没准备好",与"场次未结算"同类:
        // 返回 pending 让导播台按提示补齐,而不是抛异常当失败处理。
        // 必须在结算前统一扫一遍,避免像异常那样中途中断、只结算了一半场次。
        List<String> unjudged = unjudgedRankNames(stage);
        if (!unjudged.isEmpty()) {
            return StageSettleOutcome.pending("圈内仍有 " + unjudged.size() + " 名选手未打分(未标记退赛): "
                + String.join(", ", unjudged) + ",请先完成打分或标记退赛后再结算");
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

    /**
     * 尚未结算的排名赛场次里,「从未被任何裁判打分且未退赛」的选手姓名。
     *
     * <p>口径与 {@code settleRankMatch} 内的守卫一致:任一裁判打过即可,退赛选手不计。
     * 返回空表示所有场次都可以结算。</p>
     */
    private List<String> unjudgedRankNames(TStage stage) {
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (matchIds.isEmpty()) {
            return List.of();
        }
        List<Long> cids = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .isNotNull(TMatchParticipant::getCompetitorId)
                .select(TMatchParticipant::getCompetitorId))
            .stream()
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (cids.isEmpty()) {
            return List.of();
        }
        Map<Long, TCompetitor> compMap = settlementSupport.competitorMap(cids);
        List<Long> active = cids.stream()
            .filter(cid -> {
                TCompetitor c = compMap.get(cid);
                return c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
            })
            .toList();
        if (active.isEmpty()) {
            return List.of();
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Set<Long> judged = roundIds.isEmpty() ? Set.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds)
                    .select(TRoundScore::getCompetitorId))
                .stream()
                .map(TRoundScore::getCompetitorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return active.stream()
            .filter(cid -> !judged.contains(cid))
            .map(compMap::get)
            .filter(Objects::nonNull)
            .map(TCompetitor::getName)
            .toList();
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
        // 圈序号 / 每圈晋升名额 / 全局排名起点:与海选结算、名单取人共用同一口径
        Map<String, StageFlowSupport.CircleQuota> quotaCtx =
            StageFlowSupport.circleQuotaContext(stage, matches, "排名赛");
        Map<String, Integer> zoneQuota = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        quotaCtx.forEach((zone, quota) -> {
            zoneQuota.put(zone, quota.quota());
            zoneBase.put(zone, quota.base());
        });
        // 圈内已晋级数(支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countRankAdvancedByZone(matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            String zone = match.getDisplayZone();
            settleRankMatch(match, rc, zoneQuota.getOrDefault(zone, 0),
                zoneBase.getOrDefault(zone, 0), zoneAdvanced);
        }
    }

    /**
     * 统计排名赛各圈(displayZone)已晋级人数,每圈独立结算时用它计算剩余名额。
     *
     * <p>与海选同一口径:<b>按人去重</b>。晋级结果会同步到该选手在本赛段的全部参赛行
     * (见 {@code markManualAdvanceResult}),按行计数会把同一名晋级者重复算进名额。</p>
     */
    private Map<String, Integer> countRankAdvancedByZone(List<TMatch> matches) {
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : matches) {
            matchZone.put(m.getId(), m.getDisplayZone());
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchZone.keySet())
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        Map<Long, String> advancerZone = new HashMap<>();
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() == null) {
                continue;
            }
            advancerZone.putIfAbsent(p.getCompetitorId(),
                matchZone.getOrDefault(p.getMatchId(), ""));
        }
        Map<String, Integer> result = new HashMap<>();
        for (String zone : advancerZone.values()) {
            result.merge(zone, 1, Integer::sum);
        }
        return result;
    }

    /**
     * 结算单场排名赛:跨本场全部轮次(每个选手一个轮次)聚合多裁判×多维度分,
     * 用 RANKING 策略算总分排名,按本圈剩余名额晋级;晋级线同分并列时保持 PENDING。
     */
    private void settleRankMatch(TMatch match, RuleConfigHolder rc, int advanceQuota,
                                 int zoneBase, Map<String, Integer> zoneAdvanced) {
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, match.getId())
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            settlementSupport.markMatchSettled(match);
            return;
        }
        List<Long> partIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).toList();
        Map<Long, TCompetitor> compMap = settlementSupport.competitorMap(partIds);
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
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Map<Long, Long> scoreCountByCompetitor = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds)
                    .select(TRoundScore::getCompetitorId))
                .stream()
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
            throw new ServiceException("圈内仍有 {} 名选手未打分(未标记退赛): {},请先完成打分或标记退赛后再结算",
                unjudged.size(), unjudged);
        }

        // 分数分布在各自轮次,跨本场全部轮次汇总
        List<TRoundScore> allScores = roundIds.isEmpty() ? List.of() : roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));

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

        String zone = match.getDisplayZone();
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);

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
                    (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
            }
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
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
                }
                // 明确淘汰者:同分群之后
                for (int i = lastTiedPos + 1; i < sortedCids.size(); i++) {
                    Long cid = sortedCids.get(i);
                    markRankResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
                }
                zoneAdvanced.put(zone, alreadyAdvanced + firstTiedPos);
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
                (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match);
        }
        zoneAdvanced.put(zone, alreadyAdvanced + Math.min(remaining, sortedCids.size()));
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
