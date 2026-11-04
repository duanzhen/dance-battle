package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.RankResultVo;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.MatchScoreInput;
import com.dance.street.game.engine.scoring.MatchScoreResult;
import com.dance.street.game.engine.scoring.ScoreAggregator;
import com.dance.street.game.engine.scoring.ScoringEngine;
import com.dance.street.game.excel.ExcelUtil;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.impl.flow.StageLookup;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dance.street.game.service.impl.settle.SettlementSupport.parseCompetitorNumber;

/**
 * 排名赛结果查询与原始数据导出。
 *
 * <p>总分与名次直接调用 {@link ScoringEngine} 的 RANKING 策略计算,和海选/排名赛结算保持同一套
 * 多裁判、多维度、加权/平均/去极值口径。结果项保留每个维度下每位裁判的原始分,供名单页与导出使用。</p>
 *
 * @author duane
 */
@Service
@RequiredArgsConstructor
public class RankResultService {

    private final StageLookup stageLookup;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TCompetitorMapper competitorMapper;
    private final TRefereeMapper refereeMapper;
    private final ScoringEngine scoringEngine;

    /** 查询排名赛结果:官方总分/名次 + 维度聚合分 + 每位裁判的原始分。 */
    public RankResultVo queryRankResult(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.RANK.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅排名赛赛段支持查询排名结果");
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        ScoringConfig scoring = rc == null ? null : rc.getScoring();
        AggregateRuleEnum refereeRule = AggregateRuleEnum.fromCode(
            scoring == null ? null : scoring.getRefereeAggregateRule());
        BigDecimal trimRatio = scoring == null ? null : scoring.getTrimRatio();

        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getDisplayCol)
            .orderByAsc(TMatch::getId));
        List<Long> matchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();

        List<TMatchParticipant> participants = matchIds.isEmpty() ? List.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .isNotNull(TMatchParticipant::getCompetitorId));
        Map<Long, List<TMatchParticipant>> participantsByMatch = participants.stream()
            .filter(p -> p.getMatchId() != null)
            .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));

        List<TMatchRound> rounds = matchIds.isEmpty() ? List.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds));
        Map<Long, List<Long>> roundIdsByMatch = rounds.stream()
            .filter(r -> r.getMatchId() != null && r.getId() != null)
            .collect(Collectors.groupingBy(TMatchRound::getMatchId,
                Collectors.mapping(TMatchRound::getId, Collectors.toList())));
        List<Long> roundIds = rounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TRoundScore>> scoresByRound = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds))
                .stream()
                .filter(s -> s.getRoundId() != null)
                .collect(Collectors.groupingBy(TRoundScore::getRoundId));

        List<Long> competitorIds = participants.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> competitorsById = competitorIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(competitorIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));
        Map<Long, String> refereeNameById = referees.stream()
            .collect(Collectors.toMap(TReferee::getId,
                r -> StringUtils.defaultString(r.getName(), "裁判" + r.getId()), (a, b) -> a));

        List<DimensionConfig> dimensions = resolveDimensions(scoring, scoresByRound);
        List<RankResultVo.CompetitorItem> items = new ArrayList<>();
        for (TMatch match : matches) {
            List<TMatchParticipant> matchParticipants =
                participantsByMatch.getOrDefault(match.getId(), List.of());
            if (matchParticipants.isEmpty()) {
                continue;
            }
            // 与排名赛结算一致:退赛者保留在名单中供查看,但不参与官方名次计算。
            List<Long> matchCompetitorIds = matchParticipants.stream()
                .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct()
                .filter(cid -> {
                    TCompetitor competitor = competitorsById.get(cid);
                    return competitor == null
                        || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(competitor.getOutcomeStatus());
                })
                .toList();
            List<TRoundScore> rawScores = roundIdsByMatch.getOrDefault(match.getId(), List.of()).stream()
                .flatMap(id -> scoresByRound.getOrDefault(id, List.of()).stream())
                .toList();
            Map<Long, MatchScoreResult> scoreResults = scoringEngine.compute(MatchScoreInput.builder()
                    .matchMode(MatchModeEnum.RANKING)
                    .scoringConfig(scoring)
                    .competitorIds(matchCompetitorIds)
                    .rawScores(rawScores)
                    .build())
                .stream()
                .filter(r -> r.getCompetitorId() != null)
                .collect(Collectors.toMap(MatchScoreResult::getCompetitorId, Function.identity(), (a, b) -> a));

            Map<Long, List<TRoundScore>> rawByCompetitor = rawScores.stream()
                .filter(s -> s.getCompetitorId() != null)
                .collect(Collectors.groupingBy(TRoundScore::getCompetitorId));
            for (TMatchParticipant participant : matchParticipants) {
                Long competitorId = participant.getCompetitorId();
                if (competitorId == null) {
                    continue;
                }
                TCompetitor competitor = competitorsById.get(competitorId);
                MatchScoreResult scoreResult = scoreResults.get(competitorId);
                List<TRoundScore> ownScores = rawByCompetitor.getOrDefault(competitorId, List.of());

                RankResultVo.CompetitorItem item = new RankResultVo.CompetitorItem();
                item.setCompetitorId(competitorId);
                item.setName(competitor == null ? null : competitor.getName());
                item.setNumber(competitor == null ? null : competitor.getNumber());
                item.setMatchId(match.getId());
                item.setZone(match.getDisplayZone());
                item.setOutcomeStatus(participant.getOutcomeStatus());
                item.setFinalRank(competitor == null ? null : competitor.getFinalRank());
                item.setDimensions(buildDimensionScores(
                    ownScores, dimensions, refereeRule, trimRatio, refereeNameById));
                if (scoreResult != null && !ownScores.isEmpty()) {
                    item.setScore(scoreResult.getScoreValue());
                    item.setRankInMatch(scoreResult.getRankInMatch() == null
                        ? null : scoreResult.getRankInMatch().longValue());
                } else {
                    item.setScore(null);
                    item.setRankInMatch(null);
                }
                items.add(item);
            }
        }
        items.sort(Comparator
            .comparing((RankResultVo.CompetitorItem i) ->
                i.getScore() == null ? new BigDecimal("-1") : i.getScore())
            .reversed()
            .thenComparingLong(i -> i.getRankInMatch() == null ? Long.MAX_VALUE : i.getRankInMatch())
            .thenComparingInt(i -> parseCompetitorNumber(i.getNumber())));

        RankResultVo vo = new RankResultVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStatus(stage.getStatus());
        vo.setAggregateRule(scoring == null ? null : scoring.getAggregateRule());
        vo.setRefereeAggregateRule(scoring == null ? null : scoring.getRefereeAggregateRule());
        vo.setTrimRatio(trimRatio);
        vo.setDimensions(dimensions.stream().map(this::toDimensionInfo).toList());
        vo.setCompetitors(items);
        return vo;
    }

    /**
     * 导出排名赛原始数据:
     * 号码 / 选手名 / 每个维度下每位裁判的原始分 / 官方总分 / 排名 / 结果。
     */
    public void exportRankResult(Long stageId, HttpServletResponse response) {
        RankResultVo result = queryRankResult(stageId);
        TStage stage = stageLookup.get(stageId);
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));

        List<List<String>> head = new ArrayList<>();
        head.add(List.of("号码"));
        head.add(List.of("选手名"));
        List<RankResultVo.DimensionInfo> dimensions = result.getDimensions() == null
            ? List.of() : result.getDimensions();
        for (RankResultVo.DimensionInfo dimension : dimensions) {
            String dimensionName = StringUtils.blankToDefault(dimension.getName(), dimension.getKey());
            if (referees.isEmpty()) {
                head.add(List.of(dimensionName));
            } else {
                for (TReferee referee : referees) {
                    head.add(List.of(dimensionName + "-"
                        + StringUtils.defaultString(referee.getName(), "裁判" + referee.getId())));
                }
            }
        }
        head.add(List.of("总分"));
        head.add(List.of("排名"));
        head.add(List.of("结果"));

        List<List<Object>> rows = new ArrayList<>();
        for (RankResultVo.CompetitorItem item : result.getCompetitors()) {
            List<Object> row = new ArrayList<>();
            row.add(item.getNumber() == null ? "" : item.getNumber());
            row.add(item.getName() == null ? "" : item.getName());
            Map<String, RankResultVo.DimensionScore> byDimension = item.getDimensions() == null ? Map.of()
                : item.getDimensions().stream()
                    .filter(d -> d.getKey() != null)
                    .collect(Collectors.toMap(RankResultVo.DimensionScore::getKey, d -> d, (a, b) -> a));
            for (RankResultVo.DimensionInfo dimension : dimensions) {
                RankResultVo.DimensionScore score = byDimension.get(dimension.getKey());
                Map<Long, BigDecimal> byReferee = score == null || score.getRefereeScores() == null ? Map.of()
                    : score.getRefereeScores().stream()
                        .filter(r -> r.getRefereeId() != null)
                        .collect(Collectors.toMap(RankResultVo.RefereeScoreItem::getRefereeId,
                            RankResultVo.RefereeScoreItem::getScore, (a, b) -> a));
                if (referees.isEmpty()) {
                    row.add(score == null ? "" : plain(score.getScore()));
                } else {
                    for (TReferee referee : referees) {
                        row.add(plain(byReferee.get(referee.getId())));
                    }
                }
            }
            row.add(plain(item.getScore()));
            Long rank = item.getFinalRank() != null ? item.getFinalRank() : item.getRankInMatch();
            row.add(rank == null ? "" : rank);
            row.add(outcomeText(item.getOutcomeStatus()));
            rows.add(row);
        }

        try {
            org.dromara.common.core.utils.file.FileUtils.setAttachmentResponseHeader(
                response, "排名赛原始数据-" + stage.getName() + ".xlsx");
            response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8");
            try (jakarta.servlet.ServletOutputStream os = response.getOutputStream()) {
                ExcelUtil.exportSheets(List.of(
                    new ExcelUtil.RawSheet("排名赛原始数据", head, rows)), os);
            }
        } catch (IOException e) {
            throw new ServiceException("导出排名赛原始数据失败: {}", e.getMessage());
        }
    }

    /**
     * 维度配置优先;历史配置缺失维度时从实际打分明细补齐,保证旧数据仍可查询/导出。
     */
    private List<DimensionConfig> resolveDimensions(ScoringConfig scoring,
                                                    Map<Long, List<TRoundScore>> scoresByRound) {
        Map<String, DimensionConfig> result = new LinkedHashMap<>();
        if (scoring != null && scoring.getDimensions() != null) {
            for (DimensionConfig dimension : scoring.getDimensions()) {
                if (dimension == null || StringUtils.isBlank(dimension.getKey())
                    || result.containsKey(dimension.getKey())) {
                    continue;
                }
                result.put(dimension.getKey(), dimension);
            }
        }
        for (List<TRoundScore> scores : scoresByRound.values()) {
            for (TRoundScore score : scores) {
                String key = StringUtils.blankToDefault(score.getDimension(), StageConstants.DIMENSION_MAIN);
                if (result.containsKey(key)) {
                    continue;
                }
                DimensionConfig dimension = new DimensionConfig();
                dimension.setKey(key);
                dimension.setName(key);
                result.put(key, dimension);
            }
        }
        return new ArrayList<>(result.values());
    }

    private RankResultVo.DimensionInfo toDimensionInfo(DimensionConfig source) {
        RankResultVo.DimensionInfo target = new RankResultVo.DimensionInfo();
        target.setKey(source.getKey());
        target.setName(source.getName());
        target.setWeight(source.getWeight());
        target.setMaxScore(source.getMaxScore());
        return target;
    }

    /** 某选手的各维度结果:维度分按裁判间规则聚合,同时保留每位裁判的原始分。 */
    private List<RankResultVo.DimensionScore> buildDimensionScores(
        List<TRoundScore> ownScores,
        List<DimensionConfig> configuredDimensions,
        AggregateRuleEnum refereeRule,
        BigDecimal trimRatio,
        Map<Long, String> refereeNameById) {
        Map<String, Map<Long, BigDecimal>> byDimensionReferee = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> valuesByDimension = new LinkedHashMap<>();
        for (TRoundScore score : ownScores) {
            if (score.getScore() == null) {
                continue;
            }
            String key = StringUtils.blankToDefault(score.getDimension(), StageConstants.DIMENSION_MAIN);
            valuesByDimension.computeIfAbsent(key, k -> new ArrayList<>()).add(score.getScore());
            if (score.getRefereeId() != null) {
                byDimensionReferee.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(score.getRefereeId(), score.getScore(), BigDecimal::add);
            }
        }

        List<RankResultVo.DimensionScore> result = new ArrayList<>();
        for (DimensionConfig config : configuredDimensions) {
            RankResultVo.DimensionScore item = new RankResultVo.DimensionScore();
            item.setKey(config.getKey());
            item.setName(config.getName());
            item.setWeight(config.getWeight());
            item.setMaxScore(config.getMaxScore());
            List<BigDecimal> values = valuesByDimension.getOrDefault(config.getKey(), List.of());
            item.setScore(values.isEmpty() ? null
                : ScoreAggregator.aggregate(values, refereeRule, trimRatio));
            Map<Long, BigDecimal> byReferee = byDimensionReferee.getOrDefault(config.getKey(), Map.of());
            item.setRefereeScores(byReferee.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    RankResultVo.RefereeScoreItem refereeScore = new RankResultVo.RefereeScoreItem();
                    refereeScore.setRefereeId(e.getKey());
                    refereeScore.setRefereeName(refereeNameById.get(e.getKey()));
                    refereeScore.setScore(e.getValue());
                    return refereeScore;
                })
                .toList());
            result.add(item);
        }
        return result;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String outcomeText(String outcome) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome) {
            case "ADVANCE" -> "晋级";
            case "ELIMINATED" -> "淘汰";
            case "PENDING" -> "待定";
            case "WITHDRAWN" -> "退赛";
            default -> outcome;
        };
    }
}
