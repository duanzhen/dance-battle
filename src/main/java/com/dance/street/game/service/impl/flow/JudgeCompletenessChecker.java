package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「这一场/这个赛段判完了吗」的唯一口径。
 *
 * <p>「判完」不等于「有一条分」，而是<b>每名选手都被本场应到的每一名裁判打过</b>。
 * 这个口径此前在四处各写了一份，且互不相同：海选按「本场绑定裁判」严格判定、
 * 排名赛退化成「任一裁判打过即可」、排名赛 BATCH 自动公布按「赛段级裁判数」、
 * 多裁判累计结算（{@code settleScoredMatches}）则完全不判。同一份数据因此在不同入口
 * 得到不同结论：一个入口认为可以结算，另一个认为还没打完；或者按只有一半裁判到场
 * 的分数算出了名次。</p>
 *
 * <p><b>应到裁判的解析顺序：</b>本场绑定（{@code t_match_referee}，海选按圈绑定）
 * 优先；本场没有绑定时回落到赛段裁判（{@code t_referee_stage}，排名赛/小组赛/
 * 淘汰赛按赛段分配）；两者都没有时退化为 1（「至少一名裁判评过」，
 * 与历史数据/管理端代打的既有口径一致）。</p>
 *
 * <p>0 分与「没打分」是两件事：裁判打了 0 分会在 {@code t_round_score} 留下记录，
 * 算已判；已标记退赛（{@code WITHDRAWN}）的选手不参与判罚，不算未判。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class JudgeCompletenessChecker {

    private final TMatchRefereeMapper matchRefereeMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TCompetitorMapper competitorMapper;

    /**
     * 本场应到场的裁判集合：本场绑定优先，未绑定回落到赛段裁判。
     *
     * @return 应到裁判ID集合；两者都没有时为空集合（调用方按 1 人处理）
     */
    public Set<Long> expectedRefereeIds(TMatch match) {
        if (match == null || match.getId() == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        List<TMatchReferee> bindings = matchRefereeMapper.selectList(
            Wrappers.<TMatchReferee>lambdaQuery().eq(TMatchReferee::getMatchId, match.getId()));
        for (TMatchReferee r : bindings) {
            if (r.getRefereeId() != null) {
                ids.add(r.getRefereeId());
            }
        }
        if (!ids.isEmpty()) {
            return ids;
        }
        if (match.getStageId() != null) {
            List<TRefereeStage> stageRefs = refereeStageMapper.selectList(
                Wrappers.<TRefereeStage>lambdaQuery().eq(TRefereeStage::getStageId, match.getStageId()));
            for (TRefereeStage r : stageRefs) {
                if (r.getRefereeId() != null) {
                    ids.add(r.getRefereeId());
                }
            }
        }
        return ids;
    }

    /**
     * 本场每名选手应被打分的裁判数：应到裁判数，未配置任何裁判时退化为 1。
     */
    public int requiredRefereeCount(TMatch match) {
        return Math.max(1, expectedRefereeIds(match).size());
    }

    /**
     * 尚未判完的选手姓名（去重，空表示全员判完）。
     *
     * <p><b>只看已开始的场次（GAMING）</b>：还没开始的场次（PENDING）属于「这场还没打完」，
     * 不是「裁判没到齐」。两者混在一起会把正常的推进卡死——例如淘汰赛 VOTING 制下，
     * 季军赛要等两场半决赛结算完才会有人，若把它算作「有人没判完」，
     * 半决赛就永远结算不了，季军赛也就永远等不到人。</p>
     *
     * @param pendingMatches 参与判定的场次（调用方按自己的语义筛选：结算只看未结算场次）
     */
    public List<String> unjudgedNames(List<TMatch> pendingMatches) {
        if (pendingMatches == null || pendingMatches.isEmpty()) {
            return List.of();
        }
        // 只看已开始的场次(见方法注释):PENDING 属于"这场还没打完",由"仍有场次未结算"负责
        List<TMatch> started = pendingMatches.stream()
            .filter(m -> StageConstants.MATCH_GAMING.equals(m.getStatus()))
            .toList();
        List<Long> pendingIds = started.stream()
            .map(TMatch::getId).filter(Objects::nonNull).toList();
        if (pendingIds.isEmpty()) {
            return List.of();
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, pendingIds)
                .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            return List.of();
        }
        List<Long> compIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        // 轮次先映射回场次：打分明细要按「场次 + 选手」归属，不能把同圈其他场次的分混进来
        Map<Long, Long> roundToMatch = new HashMap<>();
        matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, pendingIds)
                    .select(TMatchRound::getId, TMatchRound::getMatchId))
            .forEach(r -> {
                if (r.getId() != null) {
                    roundToMatch.put(r.getId(), r.getMatchId());
                }
            });
        // (场次, 选手) -> 已给出分数的裁判集合
        Map<String, Set<Long>> scoredReferees = new HashMap<>();
        if (!roundToMatch.isEmpty()) {
            roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundToMatch.keySet())
                    .isNotNull(TRoundScore::getRefereeId)
                    .select(TRoundScore::getRoundId, TRoundScore::getCompetitorId, TRoundScore::getRefereeId))
                .forEach(rs -> {
                    Long mid = rs.getRoundId() == null ? null : roundToMatch.get(rs.getRoundId());
                    if (mid == null || rs.getCompetitorId() == null) {
                        return;
                    }
                    scoredReferees.computeIfAbsent(scoredKey(mid, rs.getCompetitorId()), k -> new HashSet<>())
                        .add(rs.getRefereeId());
                });
        }
        Map<Long, List<TMatchParticipant>> partsByMatch = parts.stream()
            .filter(p -> p.getMatchId() != null)
            .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        List<String> unjudged = new ArrayList<>();
        for (TMatch match : started) {
            int required = requiredRefereeCount(match);
            for (TMatchParticipant p : partsByMatch.getOrDefault(match.getId(), List.of())) {
                Long cid = p.getCompetitorId();
                if (cid == null) {
                    continue;
                }
                int scored = scoredReferees.getOrDefault(scoredKey(match.getId(), cid), Set.of()).size();
                if (scored >= required) {
                    continue;
                }
                TCompetitor c = compMap.get(cid);
                // 已标记退赛(WITHDRAWN)的选手不参与判罚,不算未判罚
                if (c != null && OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                    continue;
                }
                String name = c != null && c.getName() != null ? c.getName() : ("选手" + cid);
                if (!unjudged.contains(name)) {
                    unjudged.add(name);
                }
            }
        }
        return unjudged;
    }

    /** (场次, 选手) → 打分明细归属键 */
    private String scoredKey(Long matchId, Long competitorId) {
        return matchId + ":" + competitorId;
    }
}
