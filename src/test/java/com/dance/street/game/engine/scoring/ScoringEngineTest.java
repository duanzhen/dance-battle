package com.dance.street.game.engine.scoring;

import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.OutcomeScore;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ScoringEngine 端到端策略测试:三种打分模式 + 同分并列。
 */
@Tag("local")
class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();

    // ---------------- STANDARD: 判胜负平 ----------------

    @Test
    void winLossDraw_mapsOutcomeToScoreAndRank() {
        ScoringConfig cfg = new ScoringConfig();
        OutcomeScore rules = new OutcomeScore();
        rules.setWinScore(bd(3));
        rules.setDrawScore(bd(1));
        rules.setLossScore(bd(0));
        cfg.setOutcomeRules(rules);

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.STANDARD)
            .scoringConfig(cfg)
            .competitorIds(List.of(1L, 2L))
            .directOutcomes(Map.of(1L, "WIN", 2L, "LOSS"))
            .build();

        List<MatchScoreResult> results = engine.compute(input);
        assertResult(find(results, 1L), bd(3), 1, "WIN");
        assertResult(find(results, 2L), bd(0), 2, "LOSS");
    }

    @Test
    void winLossDraw_unjudgedCompetitorGetsNullOutcome() {
        ScoringConfig cfg = new ScoringConfig(); // 无 outcomeRules,用默认 1/0.5/0

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.STANDARD)
            .scoringConfig(cfg)
            .competitorIds(List.of(1L, 2L))
            .directOutcomes(Map.of(1L, "WIN")) // 2L 未判定
            .build();

        List<MatchScoreResult> results = engine.compute(input);
        assertResult(find(results, 1L), bd(1), 1, "WIN");
        assertResult(find(results, 2L), bd(0), 2, null);
    }

    // ---------------- VOTING: 总分制 ----------------

    @Test
    void totalScore_sumAndRank() {
        ScoringConfig cfg = new ScoringConfig();
        cfg.setAggregateRule("SUM");

        // cid1 得 10+5=15,cid2 得 20
        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.VOTING)
            .scoringConfig(cfg)
            .competitorIds(List.of(1L, 2L))
            .rawScores(List.of(rs(1L, bd(10)), rs(1L, bd(5)), rs(2L, bd(20))))
            .build();

        List<MatchScoreResult> results = engine.compute(input);
        assertResult(find(results, 2L), bd(20), 1, null); // 分高者第1
        assertResult(find(results, 1L), bd(15), 2, null);
    }

    @Test
    void totalScore_tieProducesSameRank() {
        ScoringConfig cfg = new ScoringConfig();
        cfg.setAggregateRule("SUM");

        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.VOTING)
            .scoringConfig(cfg)
            .competitorIds(List.of(1L, 2L, 3L))
            .rawScores(List.of(rs(1L, bd(10)), rs(2L, bd(10)), rs(3L, bd(5))))
            .build();

        List<MatchScoreResult> results = engine.compute(input);
        // cid1 与 cid2 同为 10 分,并列第 1;cid3 第 3(standard competition ranking)
        assertEquals(1, find(results, 1L).getRankInMatch());
        assertEquals(1, find(results, 2L).getRankInMatch());
        assertEquals(3, find(results, 3L).getRankInMatch());
    }

    // ---------------- RANKING: 多维度多裁判 ----------------

    @Test
    void multiDim_refereeAvgThenWeightedDim() {
        ScoringConfig cfg = new ScoringConfig();
        cfg.setRefereeAggregateRule("AVG");   // 多裁判间取平均
        cfg.setAggregateRule("WEIGHTED");     // 维度间加权

        DimensionConfig tech = new DimensionConfig();
        tech.setKey("TECH");
        tech.setWeight(bd("0.5"));
        DimensionConfig show = new DimensionConfig();
        show.setKey("SHOW");
        show.setWeight(bd("0.5"));
        cfg.setDimensions(List.of(tech, show));

        // cid1: TECH(80,90)->avg 85; SHOW(70,80)->avg 75; weighted=85*0.5+75*0.5=80
        // cid2: TECH(60,60)->60;      SHOW(90,90)->90;     weighted=60*0.5+90*0.5=75
        MatchScoreInput input = MatchScoreInput.builder()
            .matchMode(MatchModeEnum.RANKING)
            .scoringConfig(cfg)
            .competitorIds(List.of(1L, 2L))
            .rawScores(List.of(
                rs(1L, 11L, "TECH", bd(80)), rs(1L, 12L, "TECH", bd(90)),
                rs(1L, 11L, "SHOW", bd(70)), rs(1L, 12L, "SHOW", bd(80)),
                rs(2L, 11L, "TECH", bd(60)), rs(2L, 12L, "TECH", bd(60)),
                rs(2L, 11L, "SHOW", bd(90)), rs(2L, 12L, "SHOW", bd(90))
            ))
            .build();

        List<MatchScoreResult> results = engine.compute(input);
        assertResult(find(results, 1L), bd(80), 1, null);
        assertResult(find(results, 2L), bd(75), 2, null);
    }

    // ---------------- helpers ----------------

    private static TRoundScore rs(Long competitorId, BigDecimal score) {
        return rs(competitorId, null, null, score);
    }

    private static TRoundScore rs(Long competitorId, Long refereeId, String dimension, BigDecimal score) {
        TRoundScore s = new TRoundScore();
        s.setCompetitorId(competitorId);
        s.setRefereeId(refereeId);
        s.setDimension(dimension);
        s.setScore(score);
        return s;
    }

    private static MatchScoreResult find(List<MatchScoreResult> results, Long competitorId) {
        return results.stream()
            .filter(r -> competitorId.equals(r.getCompetitorId()))
            .findFirst()
            .orElseThrow();
    }

    private static void assertResult(MatchScoreResult r, BigDecimal expectScore, Integer expectRank, String expectOutcome) {
        assertEquals(0, expectScore.compareTo(r.getScoreValue()), "scoreValue competitor=" + r.getCompetitorId());
        assertEquals(expectRank, r.getRankInMatch(), "rank competitor=" + r.getCompetitorId());
        if (expectOutcome == null) {
            assertNull(r.getOutcomeStatus(), "outcome should be null");
        } else {
            assertEquals(expectOutcome, r.getOutcomeStatus(), "outcome competitor=" + r.getCompetitorId());
        }
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
