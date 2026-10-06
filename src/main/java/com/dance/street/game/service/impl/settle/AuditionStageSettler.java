package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.RankCalculator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import com.dance.street.game.service.impl.flow.JudgeCompletenessChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 海选赛结算:按打分明细({@code t_round_score})现算每名参赛方的总分,按分数降序排名,
 * 前 advanceCount 名标 ADVANCE,其余标 ELIMINATED。
 *
 * <p><b>结算只认明细,不读 {@code t_match_participant.score_value}。</b>那一列由「提交打分」
 * 顺带刷新,是给裁判端/大屏实时回显用的答辩化聚合,并发提交时可能落后于明细;
 * 拿它裁决晋级会静默判错(详见 {@link #sumScoresFromDetail})。</p>
 *
 * <p>晋级线上同分时创建加赛场次(二海/三海…),此时赛段保持进行中;加赛打分期间<b>不结算</b>,
 * 由导播台/管理端点「完成赛段」触发({@link #settle})——与正式圈同一个入口,裁判判完最后一人
 * 不会当场生成下一级加赛。全员判完后这次点击会结算加赛,若再次同分就生成下一级加赛并返回
 * 「需要加赛」,否则赛段正常结束。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditionStageSettler implements StageSettler {

    /** 同一圈 + 同一边界最多加赛轮数(二海~五海),之后必须人工裁决 */
    private static final int MAX_TIEBREAKER_ROUNDS = 4;

    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TStageMapper stageMapper;
    private final CompetitorOutcomeWriter outcomeWriter;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final SettlementSupport settlementSupport;
    /** 「判完了吗」的唯一口径(与排名赛/淘汰赛上分共用,口径只保留这一份) */
    private final JudgeCompletenessChecker judgeCompletenessChecker;

    @Override
    public String stageMode() {
        return StageModeEnum.AUDITION.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        // 前置守卫:还有选手一条分都没打就不许结束(正式圈与二海/加赛一视同仁)
        List<TMatch> unfinished = unfinishedMatches(stage);
        String blocked = unjudgedReason(stage, unfinished);
        if (blocked == null) {
            // 全员判完才结算:结算会在晋级线同分处当场生成加赛场次(二海/三海…)
            settleAuditionStage(stage);
            unfinished = unfinishedMatches(stage);
        }
        // 加赛未决出时统一走「需要加赛」口径:首次点是刚生成了加赛、再点是加赛还没判完,
        // 两种情况导播要做的都是去盯加赛,混进"仍有场次未完成"会被当成漏判了场次。
        List<TMatch> tiebreakers = unfinished.stream().filter(SettlementSupport::isTiebreaker).toList();
        if (!tiebreakers.isEmpty()) {
            // 手动指定模式:加赛不打分,等导播指定晋级人员;用普通 pending 提示,不弹「需要加赛」
            String reason = isManualTiebreak(stage)
                ? "海选加赛「" + tiebreakerLabel(tiebreakers.get(0))
                    + "」尚未指定晋级人员,请在导播台指定后再完成赛段"
                : tiebreakerReason(tiebreakers);
            if (blocked != null) {
                reason = reason + " " + blocked;
            }
            return isManualTiebreak(stage)
                ? StageSettleOutcome.pending(reason)
                : StageSettleOutcome.tiebreaker(reason);
        }
        if (blocked != null) {
            return StageSettleOutcome.pending(blocked);
        }
        if (!unfinished.isEmpty()) {
            return StageSettleOutcome.pending(
                "赛段仍有 " + unfinished.size() + " 场未结算,完成全部判罚后才能结束赛段");
        }
        // 到这里:正式圈与各级加赛全部判完、结算完毕 —— 本次调用产出最终排名。
        // 就在"出排名"这一刻,按打分明细把整段显示用总分(participant.score_value)重算一遍,
        // 抹平提交期并发/多回合留下的落后缓存;与名次同事务写入,保证回显/导出/取人三处同源。
        refreshParticipantTotals(stage);
        return StageSettleOutcome.completed();
    }

    /** 本赛段尚未结算的场次(正式圈 + 加赛):能否结束赛段只看它们 */
    private List<TMatch> unfinishedMatches(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return List.of();
        }
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
    }

    /** 加赛未结束的人话原因:说清是几海、哪个圈、争什么、几人同分 */
    private String tiebreakerReason(List<TMatch> tiebreakers) {
        List<String> labels = new ArrayList<>();
        for (TMatch tb : tiebreakers) {
            labels.add(tiebreakerLabel(tb));
        }
        return "海选出现同分,需要加赛:" + String.join("、", labels)
            + "。请裁判完成加赛打分后再点「完成赛段」结束赛段。";
    }

    /** 加赛场次标签,如「第2圈 二海(晋级名额 3 人同分)」 */
    private String tiebreakerLabel(TMatch tb) {
        long people = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, tb.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        return zoneLabel(tb.getDisplayZone()) + tiebreakerRoundName(tiebreakerDepth(tb))
            + "(" + tiebreakerTag(tb.getRemark()) + " " + people + " 人同分)";
    }

    /** 圈标签:displayZone「ZONE-2」→「第2圈 」,无圈时为空 */
    private String zoneLabel(String zone) {
        if (zone == null || zone.isBlank()) {
            return "";
        }
        int idx = zone.lastIndexOf('-');
        String no = idx >= 0 ? zone.substring(idx + 1) : zone;
        return "第" + no + "圈 ";
    }

    /** 加赛深度:场次名里「加赛」出现的次数(1=二海,2=三海…),与导出 sheet 同名 */
    private int tiebreakerDepth(TMatch tb) {
        String name = tb.getName() == null ? "" : tb.getName();
        int depth = 0;
        for (int i = name.indexOf("加赛"); i >= 0; i = name.indexOf("加赛", i + 2)) {
            depth++;
        }
        return Math.max(1, depth);
    }

    private String tiebreakerRoundName(int depth) {
        return switch (depth) {
            case 1 -> "二海";
            case 2 -> "三海";
            case 3 -> "四海";
            case 4 -> "五海";
            default -> "加赛" + depth;
        };
    }

    /** 加赛争的边界标签:remark 形如「同分加赛,晋级名额,3人」,取第二段 */
    private String tiebreakerTag(String remark) {
        if (remark == null) {
            return "晋级名额";
        }
        String[] parts = remark.split(",");
        return parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "晋级名额";
    }

    /**
     * 还没打完分时的拦截原因:返回 null 表示所有人都已判完,可以结算。
     *
     * <p>此前只在二海(同分加赛)上拦,正式圈整圈没判也能直接结束,等于把没打分的人
     * 当 0 分淘汰掉;现在正式圈与加赛一视同仁。</p>
     *
     * <p>「判完」的口径不是「有一条分」,而是「本场(本圈)绑定的每一名裁判都打过分」
     * ——多裁判赛场只到一个裁判的分就结算,会把其余裁判的分整段丢掉,晋级线判错。</p>
     */
    private String unjudgedReason(TStage stage, List<TMatch> pending) {
        if (stage == null || stage.getId() == null) {
            return null;
        }
        // 手动指定模式下的加赛场次不打分,不参与「有没有判完」的判定
        List<TMatch> toCheck = isManualTiebreak(stage)
            ? pending.stream().filter(m -> !SettlementSupport.isTiebreaker(m)).toList()
            : pending;
        List<String> unjudged = judgeCompletenessChecker.unjudgedNames(toCheck);
        if (unjudged.isEmpty()) {
            return null;
        }
        int shown = Math.min(unjudged.size(), 8);
        String names = String.join("、", unjudged.subList(0, shown)) + (unjudged.size() > shown ? " 等" : "");
        return "海选还有 " + unjudged.size() + " 位选手未判完(" + names + "),本场裁判需逐人打完分才能结束赛段;"
            + "确实不上场的选手请标记退赛,或由裁判打 0 分(0 分不参与晋级)";
    }

    /** 本赛段是否为「海选加赛手动指定晋级」模式(rule_config.tiebreakMode=MANUAL)。 */
    private boolean isManualTiebreak(TStage stage) {
        if (stage == null) {
            return false;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        return rc != null && "MANUAL".equals(rc.resolveTiebreakMode());
    }

    private boolean isManualTiebreakByStageId(Long stageId) {
        return stageId != null && isManualTiebreak(stageMapper.selectById(stageId));
    }

    /** 整段结算:逐场结算尚未结算的海选场(圈名额/排名起点统一口径) */
    private void settleAuditionStage(TStage stage) {
        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return;
        }
        // 圈名额/全局排名起点统一计算(与二海/三海单场自动结算共用,避免口径分叉)
        Map<String, StageFlowSupport.CircleQuota> zoneCtx =
            StageFlowSupport.circleQuotaContext(stage, matches, "海选");
        // 圈内已晋级数(含已结算正式圈与加赛,支持重复结算幂等)
        Map<String, Integer> zoneAdvanced = countAuditionAdvancedByZone(matches);

        for (TMatch match : matches) {
            if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
                continue;
            }
            String zone = match.getDisplayZone();
            StageFlowSupport.CircleQuota qb = zoneCtx.get(zone);
            if (qb == null) {
                continue;
            }
            settleAuditionMatch(match, qb.quota(), qb.base(), zoneAdvanced);
        }
    }

    /**
     * 按 {@code t_round_score} 重算每个参赛行的显示用总分({@code participant.score_value})。
     *
     * <p>提交打分时刷新这一列只是给裁判端/大屏做实时回显,并发提交(各事务看不到彼此未提交的行)
     * 时会落后;在赛段结算完成、产出最终排名这一刻以明细为准整段重算一次,把落后抹平。
     * 名次/晋级不依赖这一列(另行按明细裁决),这一列只服务回显与下游按分数取人。</p>
     */
    private void refreshParticipantTotals(TStage stage) {
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId()));
        List<Long> matchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        if (matchIds.isEmpty()) {
            return;
        }
        Map<Long, Long> matchByRound = new HashMap<>();
        matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .select(TMatchRound::getId, TMatchRound::getMatchId))
            .forEach(r -> {
                if (r.getId() != null && r.getMatchId() != null) {
                    matchByRound.putIfAbsent(r.getId(), r.getMatchId());
                }
            });
        if (matchByRound.isEmpty()) {
            return;
        }
        // "matchId:competitorId" -> 明细累计分
        Map<String, BigDecimal> totals = new HashMap<>();
        roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                .in(TRoundScore::getRoundId, matchByRound.keySet())
                .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_SCORE)
                .select(TRoundScore::getRoundId, TRoundScore::getCompetitorId, TRoundScore::getScore))
            .forEach(s -> {
                Long matchId = s.getRoundId() == null ? null : matchByRound.get(s.getRoundId());
                if (matchId != null && s.getCompetitorId() != null && s.getScore() != null) {
                    totals.merge(matchId + ":" + s.getCompetitorId(), s.getScore(), BigDecimal::add);
                }
            });
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .isNotNull(TMatchParticipant::getCompetitorId));
        int changed = 0;
        for (TMatchParticipant p : parts) {
            BigDecimal total = totals.getOrDefault(p.getMatchId() + ":" + p.getCompetitorId(), BigDecimal.ZERO);
            if (p.getScoreValue() != null && p.getScoreValue().compareTo(total) == 0) {
                continue;
            }
            participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getId, p.getId())
                .set(TMatchParticipant::getScoreValue, total));
            changed++;
        }
        if (changed > 0) {
            log.info("海选结算:按明细重算 {} 行显示用总分(participant.score_value)", changed);
        }
    }

    /**
     * 统计海选各圈(displayZone)已晋级人数,每圈独立结算时用它计算剩余名额。
     *
     * <p><b>必须按人去重</b>:加赛结算会把晋级结果同步回该选手在本赛段的全部参赛行
     * (原始圈 + 各级加赛),同一名晋级者因此会留下多行 ADVANCE。按行计数会把二海晋级者
     * 重复算进名额(二海 1 人晋级、剩 2 人进三海时,三海查到"名额已满"而把两人全淘汰,
     * 本圈少一个晋级者——正是"选手没进下一赛段"那类现场事故)。</p>
     */
    private Map<String, Integer> countAuditionAdvancedByZone(List<TMatch> matches) {
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : matches) {
            matchZone.put(m.getId(), m.getDisplayZone());
        }
        List<TMatchParticipant> parts = participantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchZone.keySet())
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        // competitorId -> 所在圈:同一选手多行只留第一行(其所在圈唯一)
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
     * 本场每名参赛方的累计分:从打分明细({@code t_round_score})现算,按参赛方求和。
     *
     * <p><b>结算只认这份现算结果,不读 {@code t_match_participant.score_value}。</b>
     * 那一列是给裁判端/大屏实时回显用的答辩化聚合,由「提交打分」写路径顺带刷新;
     * 两名裁判在不同实例上并发提交时,后写的那个事务可能只看到自己那一半的明细,
     * 于是把「只含部分裁判」的和写进该列——后续提交会自愈,但若正好没人再提交,
     * 拿它做晋级裁决就会静默判错。结算时以明细为准,这条风险从"静默错晋级"降级为
     * "回显短暂滞后",不再影响谁晋级。</p>
     *
     * <p>海选是「逐选手一个轮次」,同一名选手的分可能分散在多个轮次里,因此按本场
     * 全部轮次跨轮汇总;口径与实时回显的 {@code accumulateAuditionScores} 一致(明细求和)。</p>
     */
    private Map<Long, BigDecimal> sumScoresFromDetail(TMatch match) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        if (match == null || match.getId() == null) {
            return totals;
        }
        List<Long> roundIds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery()
                    .eq(TMatchRound::getMatchId, match.getId())
                    .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        if (roundIds.isEmpty()) {
            return totals;
        }
        roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                .in(TRoundScore::getRoundId, roundIds)
                .select(TRoundScore::getCompetitorId, TRoundScore::getScore))
            .forEach(rs -> {
                if (rs.getCompetitorId() == null || rs.getScore() == null) {
                    return;
                }
                totals.merge(rs.getCompetitorId(), rs.getScore(), BigDecimal::add);
            });
        return totals;
    }

    /** 结算单个海选场次(正式圈或加赛):定晋级/淘汰、必要时创建下一级加赛 */
    private void settleAuditionMatch(TMatch match, int advanceQuota,
                                     int zoneBase,
                                     Map<String, Integer> zoneAdvanced) {
        // 手动指定晋级模式的加赛:不打分,按导播指定的人员结算(晋级者按号码牌升序排),其余淘汰
        if (settlementSupport.isTiebreaker(match) && isManualTiebreakByStageId(match.getStageId())) {
            settleManualTiebreakMatch(match, advanceQuota, zoneBase, zoneAdvanced);
            return;
        }
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
        List<TMatchParticipant> active = parts.stream()
            .filter(p -> p.getCompetitorId() != null)
            .filter(p -> {
                TCompetitor c = compMap.get(p.getCompetitorId());
                return c == null || !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
            })
            .toList();
        if (active.isEmpty()) {
            // 圈内全员退赛:直接置为已结算,不产出晋级者
            settlementSupport.markMatchSettled(match);
            return;
        }
        // 剩余晋级名额:按圈独立计算,加赛场次只争本圈尚未确定的晋级位
        String zone = match.getDisplayZone();
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);

        // 分数一律从打分明细现算,不读 participant.score_value(见 sumScoresFromDetail):
        // 拿答辩化的聚合列裁决晋级,一旦那个列落后就会静默判错。
        Map<Long, BigDecimal> detailTotals = sumScoresFromDetail(match);
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : active) {
            if (p.getCompetitorId() != null) {
                scores.put(p.getCompetitorId(), detailTotals.getOrDefault(p.getCompetitorId(), BigDecimal.ZERO));
            }
        }
        List<Long> sortedCids = new ArrayList<>(scores.keySet());
        sortedCids.sort((a, b) -> {
            BigDecimal sa = scores.getOrDefault(a, BigDecimal.ZERO);
            BigDecimal sb = scores.getOrDefault(b, BigDecimal.ZERO);
            int cmp = sb.compareTo(sa);
            if (cmp != 0) {
                return cmp;
            }
            // 非晋级线的同分:按签到时抽签的号码牌升序定先后(名次唯一,不并列)
            TCompetitor ca = compMap.get(a);
            TCompetitor cb = compMap.get(b);
            int na = ca == null ? Integer.MAX_VALUE : SettlementSupport.parseCompetitorNumber(ca.getNumber());
            int nb = cb == null ? Integer.MAX_VALUE : SettlementSupport.parseCompetitorNumber(cb.getNumber());
            return Integer.compare(na, nb);
        });
        // 名次 = 上面的排序位次(分数降序、同分按号码牌);晋级线上的并列由二海决出后再回写名次
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sortedCids.size(); i++) {
            ranks.put(sortedCids.get(i), i + 1);
        }

        // 0 分选手(弃权/缺席,含未打分)不参与晋级,也不参与同分加赛;
        // 正分人数不足晋级名额时,剩余名额空缺(下一赛段对应位置轮空)
        int positiveCount = 0;
        for (Long cid : sortedCids) {
            if (scores.get(cid).compareTo(BigDecimal.ZERO) > 0) {
                positiveCount++;
            }
        }

        // 名次段加赛(名次线上并列、双方结果早已确定):只决先后,不改晋级/淘汰结果
        Map<Long, String> keepOutcome = new HashMap<>();
        String matchRemark = match.getRemark();
        boolean rankTiebreak = settlementSupport.isTiebreaker(match)
            && matchRemark != null && matchRemark.contains("名次段");
        if (rankTiebreak) {
            for (TCompetitor c : compMap.values()) {
                if (c.getOutcomeStatus() != null
                    && !OutcomeStatusEnum.PENDING.getCode().equals(c.getOutcomeStatus())) {
                    keepOutcome.put(c.getId(), c.getOutcomeStatus());
                }
            }
        }

        // 剩余名额已满,本场(加赛)所有人淘汰
        if (remaining <= 0) {
            for (int i = 0; i < sortedCids.size(); i++) {
                markAuditionResult(sortedCids.get(i),
                    keepOutcome.getOrDefault(sortedCids.get(i), OutcomeStatusEnum.ELIMINATED.getCode()),
                    (long) (zoneBase + i + 1 + alreadyAdvanced), ranks, match.getId());
            }
            settlementSupport.markMatchSettled(match);
            log.info("海选赛场次[{}]结算:晋级名额已满,{}名同分选手淘汰", match.getId(), sortedCids.size());
            return;
        }

        // 检测晋级线上的同分情况(仅正分选手参与;正分人数不足名额时直接晋级,不产生加赛)
        if (remaining < positiveCount) {
            BigDecimal cutoffScore = scores.get(sortedCids.get(remaining - 1));
            // 统计与 cutoffScore 同分的所有选手
            List<Long> tiedAtCutoff = new ArrayList<>();
            for (Long cid : sortedCids) {
                if (scores.get(cid).compareTo(cutoffScore) == 0) {
                    tiedAtCutoff.add(cid);
                }
            }
            // 同分导致晋级人数超限,需要加赛
            if (tiedAtCutoff.size() > 1 && remaining <= sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1))) {
                // 明确晋级者:排在 tiedAtCutoff 中第一名之前的所有人
                int firstTiedPos = sortedCids.indexOf(tiedAtCutoff.get(0));
                for (int i = 0; i < firstTiedPos; i++) {
                    Long cid = sortedCids.get(i);
                    markAuditionResult(cid, OutcomeStatusEnum.ADVANCE.getCode(),
                        (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
                }
                // 明确淘汰者:排在 tiedAtCutoff 中最后一名之后的所有人
                int lastTiedPos = sortedCids.indexOf(tiedAtCutoff.get(tiedAtCutoff.size() - 1));
                for (int i = lastTiedPos + 1; i < sortedCids.size(); i++) {
                    Long cid = sortedCids.get(i);
                    markAuditionResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(),
                        (long) (zoneBase + i + 1 + alreadyAdvanced), ranks, match.getId());
                }
                zoneAdvanced.put(zone, alreadyAdvanced + firstTiedPos);
                // 同分选手保持 PENDING,创建加赛场次
                createTiebreakerMatch(match, tiedAtCutoff);
                settlementSupport.markMatchSettled(match);
                log.info("海选赛场次[{}]出现{}名同分选手,已创建加赛", match.getId(), tiedAtCutoff.size());
                return;
            }
        }

        // 正常结算:0 分选手永不晋级;正分选手按分数从高到低取前 min(remaining, positiveCount) 名,
        // 名额不足时剩余名额空缺(下一赛段轮空)
        int advanced = 0;
        int zeroCount = 0;
        // 0 分的名次起点:名额已被正分选手占满时自然接在最后(不并列、不重号);
        // 名额没满(空缺/轮空)时从本圈名额之外开始,把空位真正留空。
        int zeroRankBase = Math.max(alreadyAdvanced + positiveCount, advanceQuota);
        for (int i = 0; i < sortedCids.size(); i++) {
            Long cid = sortedCids.get(i);
            boolean eligible = scores.get(cid).compareTo(BigDecimal.ZERO) > 0;
            boolean advance = eligible && advanced < remaining;
            if (advance) {
                advanced++;
            }
            String computed = advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode();
            if (eligible) {
                markAuditionResult(cid, keepOutcome.getOrDefault(cid, computed),
                    (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
            } else {
                // 0 分既不占名额、也不占名次段:名次从本圈名额之外开始排。
                // 否则「15 人有分 + 本圈 16 个名额」时,0 分选手会稳稳占住第 16 名,
                // 下一赛段按「圈内第 1~N 名」取人就会把他当候选人捞进去——
                // 现场的体感就是"0 分也晋级了"。空缺的那个位置要留空(下一赛段轮空)。
                int zeroRank = zeroRankBase + zeroCount + 1;
                zeroCount++;
                ranks.put(cid, zeroRank);
                markAuditionResult(cid, keepOutcome.getOrDefault(cid, computed),
                    (long) (zoneBase + zeroRank), ranks, match.getId());
            }
        }
        zoneAdvanced.put(zone, alreadyAdvanced + advanced);

        settlementSupport.markMatchSettled(match);

        log.info("海选赛场次[{}]已结算,共{}名选手,正分{}名,晋级{}名(0分选手不晋级)", match.getId(),
            sortedCids.size(), positiveCount, advanced);
    }

    /**
     * 结算「手动指定晋级」的海选加赛:分数不参与——按导播指定名单定晋级,晋级排序用号码牌从小到大。
     * 未指定时保持未结算(由 {@code settle()} 给出明确提示)。
     */
    private void settleManualTiebreakMatch(TMatch match, int advanceQuota, int zoneBase,
                                           Map<String, Integer> zoneAdvanced) {
        List<Long> designated = AuditionTiebreakResult.read(match.getResultJson());
        if (designated.isEmpty()) {
            return;   // 还没指定:settle() 会提示"尚未指定晋级人员"
        }
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        Set<Long> partIds = new LinkedHashSet<>();
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                partIds.add(p.getCompetitorId());
            }
        }
        if (partIds.isEmpty()) {
            settlementSupport.markMatchSettled(match);
            return;
        }
        Map<Long, TCompetitor> compMap = settlementSupport.competitorMap(new ArrayList<>(partIds));
        Comparator<Long> byNumber = Comparator.comparingInt(cid -> {
            TCompetitor c = compMap.get(cid);
            return c == null ? Integer.MAX_VALUE : SettlementSupport.parseCompetitorNumber(c.getNumber());
        });
        String zone = match.getDisplayZone();
        int alreadyAdvanced = zoneAdvanced.getOrDefault(zone, 0);
        int remaining = Math.max(0, advanceQuota - alreadyAdvanced);
        // 指定晋级的人(限定在本场参赛方内)按号码牌升序,取满剩余名额
        List<Long> orderedAdvance = designated.stream()
            .filter(partIds::contains)
            .sorted(byNumber)
            .limit(remaining)
            .toList();
        Set<Long> advanceSet = new HashSet<>(orderedAdvance);
        List<Long> orderedRest = partIds.stream()
            .filter(cid -> !advanceSet.contains(cid))
            .sorted(byNumber)
            .toList();
        List<Long> ordered = new ArrayList<>(orderedAdvance);
        ordered.addAll(orderedRest);
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            ranks.put(ordered.get(i), i + 1);
        }
        for (int i = 0; i < ordered.size(); i++) {
            Long cid = ordered.get(i);
            String outcome = i < orderedAdvance.size()
                ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode();
            markAuditionResult(cid, outcome, (long) (zoneBase + alreadyAdvanced + i + 1), ranks, match.getId());
        }
        zoneAdvanced.put(zone, alreadyAdvanced + orderedAdvance.size());
        settlementSupport.markMatchSettled(match);
        log.info("海选手动加赛[{}]结算:指定晋级{}名(按号码牌升序),其余淘汰",
            match.getId(), orderedAdvance.size());
    }

    private void markAuditionResult(Long cid, String outcome, Long finalRank,
                                    Map<Long, Integer> ranks, Long matchId) {
        outcomeWriter.writeResult(cid, outcome, finalRank);

        TMatchParticipant pUpd = new TMatchParticipant();
        pUpd.setRankInMatch(ranks.get(cid) != null ? ranks.get(cid).longValue() : null);
        pUpd.setOutcomeStatus(outcome);
        participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, matchId)
            .eq(TMatchParticipant::getCompetitorId, cid));

        TMatch match = matchMapper.selectById(matchId);
        // 二海(同分加赛)结算:把晋级/淘汰结果同步回该选手在所有场次(原始海选场、中间加赛场)的
        // 参赛方行,避免原始场次参赛方状态停留在 PENDING 而一直显示"进行中";
        // 普通海选场每个选手只有一行,无需跨场次同步
        if (settlementSupport.isTiebreaker(match)) {
            List<Long> stageMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, match.getStageId())
                    .select(TMatch::getId))
                .stream().map(TMatch::getId).toList();
            if (!stageMatchIds.isEmpty()) {
                TMatchParticipant sync = new TMatchParticipant();
                sync.setOutcomeStatus(outcome);
                participantMapper.update(sync, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .in(TMatchParticipant::getMatchId, stageMatchIds)
                    .eq(TMatchParticipant::getCompetitorId, cid));
            }
            // 二海/三海结果回写原始海选场的本场排名(按最终排名顺序重排同分小组)
            syncTiebreakerOriginalRank(match);
        }
    }

    /**
     * 二海/三海结算后,把同分小组在原始海选场(一海)的本场排名写回:
     * 小组基准名次 = 原场竞争性排名(同分并列时的名次),按各成员最终排名(finalRank,
     * 即加赛逐级决出的顺序)依次顺延;尚未出结果(PENDING)的成员保持不动。
     *
     * <p><b>小组口径是整条加赛链的根场次(二海)的参与方,不是当前这一级加赛的参与方。</b>
     * 连环加赛里,在上一级(二海)就已经直接晋级的人不在下一级(三海)的名单里;只按当前
     * 场次取小组,他们的原场名次永远写不上,而下一赛段是按「圈内第 1~N 名」取人的
     * (见 {@code TStageRosterServiceImpl#groupPass})——名次为空就被整段漏掉,
     * 现场表现就是「二海晋级的 2 个人没掉了,只剩三海那 1 个」。</p>
     */
    private void syncTiebreakerOriginalRank(TMatch tbMatch) {
        if (tbMatch == null || tbMatch.getStageId() == null) {
            return;
        }
        // 找原始海选场:同赛段同圈、非加赛、displayRow 小于加赛场次的最近一场(加赛链逐级回退)
        List<TMatch> candidates = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, tbMatch.getStageId())
            .eq(tbMatch.getDisplayZone() != null, TMatch::getDisplayZone, tbMatch.getDisplayZone())
            .isNull(tbMatch.getDisplayZone() == null, TMatch::getDisplayZone)
            .orderByDesc(TMatch::getDisplayRow));
        TMatch original = null;
        for (TMatch m : candidates) {
            if (settlementSupport.isTiebreaker(m)) {
                continue;
            }
            if (m.getDisplayRow() != null && tbMatch.getDisplayRow() != null
                && m.getDisplayRow() < tbMatch.getDisplayRow()) {
                original = m;
                break;
            }
        }
        if (original == null) {
            return;
        }
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, original.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        if (parts.isEmpty()) {
            return;
        }
        // 原场竞争性排名(同分并列),用于确定同分小组的基准名次
        Map<Long, BigDecimal> detailTotals = sumScoresFromDetail(original);
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (TMatchParticipant p : parts) {
            if (p.getCompetitorId() != null) {
                scores.put(p.getCompetitorId(), detailTotals.getOrDefault(p.getCompetitorId(), BigDecimal.ZERO));
            }
        }
        Map<Long, Integer> compRanks = RankCalculator.rank(scores);
        // 同分小组 = 整条加赛链根场次(二海)的参与方,含在上一级就已直接晋级的成员
        TMatch rootTiebreaker = rootTiebreakerOf(tbMatch);
        List<TMatchParticipant> tbParts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, rootTiebreaker.getId())
            .isNotNull(TMatchParticipant::getCompetitorId));
        List<Long> groupIds = tbParts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        if (groupIds.isEmpty()) {
            return;
        }
        Integer baseRank = groupIds.stream()
            .map(compRanks::get).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        if (baseRank == null) {
            return;
        }
        // 连环加赛未全部决出时先不回写(避免中间名次错误),等全组有最终排名后再统一写回
        Map<Long, TCompetitor> compById = settlementSupport.competitorMap(groupIds);
        boolean anyUnresolved = false;
        for (Long gid : groupIds) {
            TCompetitor c = compById.get(gid);
            if (c == null || OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            if (c.getFinalRank() == null) {
                anyUnresolved = true;
                break;
            }
        }
        if (anyUnresolved) {
            return;
        }
        // 已出结果(有最终排名)的成员写回原场名次:晋级者先按 finalRank 升序占名额段
        // (base, base+1, …),没晋级的(含加赛打 0 分的)从本圈名额之外排起。
        // 名额段里必须只有晋级者——否则 0 分/被淘汰的人会占住「圈内第 1~N 名」的末端,
        // 被下一赛段的出口当候选人捞进名单,现场就是"0 分也晋级了"。
        int quota = circleQuotaOf(original);
        List<TCompetitor> resolved = groupIds.stream()
            .map(compById::get).filter(Objects::nonNull)
            .filter(c -> !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus()))
            .filter(c -> c.getFinalRank() != null)
            .sorted(java.util.Comparator
                .comparing((TCompetitor c) ->
                    OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus()) ? 0 : 1)
                .thenComparing(TCompetitor::getFinalRank))
            .toList();
        long cursor = baseRank.longValue();
        long tail = -1L;
        for (TCompetitor c : resolved) {
            long rank;
            if (OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())) {
                rank = cursor++;
            } else {
                if (tail < 0) {
                    tail = Math.max(cursor, (long) quota + 1);
                }
                rank = tail++;
            }
            TMatchParticipant upd = new TMatchParticipant();
            upd.setRankInMatch(rank);
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, original.getId())
                .eq(TMatchParticipant::getCompetitorId, c.getId()));
        }
    }

    /**
     * 本圈晋级名额:用于把同分小组里"没进名额段"的成员排到名额之外。
     * 解析失败(配置非法等)时返回 0,等于退回"按 finalRank 顺序紧凑排列"的旧口径,不影响结算。
     */
    private int circleQuotaOf(TMatch circleMatch) {
        try {
            TStage stage = circleMatch == null || circleMatch.getStageId() == null
                ? null : stageMapper.selectById(circleMatch.getStageId());
            if (stage == null) {
                return 0;
            }
            List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
            StageFlowSupport.CircleQuota quota =
                StageFlowSupport.circleQuotaContext(stage, matches, "海选").get(circleMatch.getDisplayZone());
            return quota == null ? 0 : quota.quota();
        } catch (RuntimeException e) {
            log.warn("解析海选圈名额失败,加赛名次按旧口径回写: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 沿 {@code parent_match_id} 上溯到这条加赛链的根场次(直接挂在正式圈下的那场加赛)。
     *
     * <p>二海/三海/四海…共用同一批同分选手,名字里用 remark 计数不可靠,
     * 来源链(加赛的 parent_match_id 指向上一级)才是唯一事实源。</p>
     */
    private TMatch rootTiebreakerOf(TMatch tbMatch) {
        TMatch current = tbMatch;
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            Long parentId = current.getParentMatchId();
            if (parentId == null) {
                break;
            }
            TMatch parent = matchMapper.selectById(parentId);
            if (parent == null || !settlementSupport.isTiebreaker(parent)) {
                break;
            }
            current = parent;
        }
        return current;
    }

    /** 创建加赛场次:只有同分选手参与,胜负决出后由 completeStage 再次结算。 */
    private void createTiebreakerMatch(TMatch parentMatch, List<Long> tiedCompetitorIds) {
        createTiebreakerMatch(parentMatch, tiedCompetitorIds, "晋级名额");
    }

    /**
     * 把原场次(圈)的裁判绑定复制到加赛场次。
     *
     * <p>分圈海选时裁判只判自己绑定的圈(见 RefereeMatchController#allowedMatchIds),
     * 加赛场次若不继承绑定,该圈的裁判就看不到二海,赛段会卡在"仍有场次未结算"。</p>
     */
    private void copyMatchReferees(TMatch source, TMatch target) {
        for (Long refereeId : resolvedRefereeIds(source)) {
            TMatchReferee row = new TMatchReferee();
            row.setTournamentId(target.getTournamentId());
            row.setMatchId(target.getId());
            row.setRefereeId(refereeId);
            matchRefereeMapper.insert(row);
        }
    }

    /**
     * 本场次应到场的裁判集合:本场(本圈)的直接绑定。
     *
     * <p>这是「谁该打分」的唯一解析点——加赛复制绑定、判定是否判完都走这里,
     * 保证两处口径不会分叉(复制的是这几个人,要等的就是这几个人)。</p>
     */
    private Set<Long> resolvedRefereeIds(TMatch match) {
        if (match == null || match.getId() == null) {
            return Set.of();
        }
        List<TMatchReferee> bindings = matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
            .eq(TMatchReferee::getMatchId, match.getId()));
        Set<Long> refereeIds = new LinkedHashSet<>();
        for (TMatchReferee r : bindings) {
            if (r.getRefereeId() != null) {
                refereeIds.add(r.getRefereeId());
            }
        }
        return refereeIds;
    }

    /**
     * 创建加赛场次(同 stage 内的新 match,由它决定原场中悬而未决的名次)。
     *
     * @param tag 这条边界的人话标签(如"晋级名额""名次段第24名"),写入 remark:
     *            既用于导出分组,也用于「同一边界最多加赛几轮」的计数
     */
    private void createTiebreakerMatch(TMatch parentMatch, List<Long> tiedCompetitorIds, String tag) {
        long tiebreakerCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, parentMatch.getStageId())
            .eq(parentMatch.getDisplayZone() != null, TMatch::getDisplayZone, parentMatch.getDisplayZone())
            .likeRight(TMatch::getRemark, SettlementSupport.TIEBREAKER_PREFIX)
            .like(TMatch::getRemark, tag));
        if (tiebreakerCount >= MAX_TIEBREAKER_ROUNDS) {
            throw new ServiceException(
                "海选「{}」已连续 {} 轮同分加赛仍未决出,请人工裁决(如调整打分或重置赛段后重排)",
                tag, tiebreakerCount);
        }
        TMatch tb = new TMatch();
        tb.setTournamentId(parentMatch.getTournamentId());
        tb.setStageId(parentMatch.getStageId());
        tb.setName(parentMatch.getName() + "-加赛");
        tb.setStatus(StageConstants.MATCH_PENDING);
        tb.setMatchMode(parentMatch.getMatchMode());
        // 显式标记加赛(不再只靠 remark 文本识别);来源场次一并落库便于回溯
        tb.setMatchType(StageConstants.MATCH_TYPE_TIEBREAKER);
        tb.setParentMatchId(parentMatch.getId());
        // 加赛归属原圈(同 zone),结算时只争本圈剩余名额
        tb.setDisplayZone(parentMatch.getDisplayZone());
        tb.setDisplayRow(parentMatch.getDisplayRow() != null ? parentMatch.getDisplayRow() + 1L : 1L);
        tb.setDisplayCol(2L);
        tb.setRemark(SettlementSupport.TIEBREAKER_PREFIX + "," + tag + "," + tiedCompetitorIds.size() + "人");
        matchMapper.insert(tb);

        // 加赛场次沿用原圈的裁判绑定:分圈海选里裁判只判自己绑定的圈,
        // 不复制绑定会导致二海的场次谁都看不到、判罚不了,赛段卡住无法结算。
        copyMatchReferees(parentMatch, tb);

        // 每个同分选手一个参赛位(entry);加赛场次一个回合,「谁」由 participant 承载。
        // 打分明细按 (回合, 选手) 区分到人,裁判端逐人展示与打分。
        for (int i = 0; i < tiedCompetitorIds.size(); i++) {
            TMatchParticipant p = new TMatchParticipant();
            p.setTournamentId(tb.getTournamentId());
            p.setMatchId(tb.getId());
            p.setCompetitorId(tiedCompetitorIds.get(i));
            p.setDisplaySlotIndex((long) (i + 1));
            p.setSlotKind(StageConstants.SLOT_PLAYER);
            p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
            participantMapper.insert(p);
        }

        TMatchRound round = new TMatchRound();
        round.setTournamentId(tb.getTournamentId());
        round.setMatchId(tb.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(round);

        // 自动开始加赛
        tb.setStatus(StageConstants.MATCH_GAMING);
        matchMapper.updateById(tb);
        // 加赛创建后必须推送:裁判端需要看到新场次才能打分,导播端需要知道赛段尚未完成
        refereeSseNotifier.notifyMatch(tb.getStageId(), tb.getId(), "match");
        tournamentEventNotifier.notify(tb.getTournamentId(), tb.getStageId(), tb.getId(), "match");

        log.info("加赛场次[{}]已创建并自动开始,{}名选手参与", tb.getId(), tiedCompetitorIds.size());
    }

}
