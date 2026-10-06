package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeMatchInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeParticipantInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeRoundInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeStageInfo;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.MatchFormat;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.service.IRefereeMatchService;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.impl.settle.AuditionAdvanceInfoSupport;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 裁判端判罚页的信息读取与判罚提交。
 *
 * <p>从控制器搬下来时保持原有口径不变,几个容易踩的点仍在注释里:
 * 分圈海选的圈级可见性、排名赛未公布时隐藏汇总分、海选按号码排序而非槽位、
 * 以及"当前场次没有参赛方时自动切到有人的进行中场次"。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RefereeMatchServiceImpl implements IRefereeMatchService {

    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final ITMatchResultService matchResultService;
    private final ITRefereeStageService refereeStageService;
    /** 海选晋级人数展示口径(与结算同一套) */
    private final AuditionAdvanceInfoSupport auditionAdvanceInfoSupport;

    @Override
    public RefereeMatchVo myMatch(Long tournamentId, Long refereeId, String refereeName,
                                  Long stageId, Long matchId) {
        List<Long> stageIds = refereeStageService.getStageIdsByRefereeId(refereeId);
        if (stageIds.isEmpty()) {
            throw new ServiceException("暂未分配赛段，请联系管理员");
        }

        List<TStage> stages = stageMapper.selectList(
            Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, tournamentId)
                .in(TStage::getId, stageIds)
                .eq(TStage::getStatus, StageConstants.STAGE_GAMING)
                .orderByAsc(TStage::getId));
        if (stages.isEmpty()) {
            throw new ServiceException("当前没有进行中的赛段");
        }

        // 自动定位当前赛段:优先 stageId → matchId 所在赛段 → 第一个进行中赛段
        TStage stage = null;
        if (stageId != null) {
            stage = stages.stream().filter(s -> s.getId().equals(stageId)).findFirst().orElse(null);
        }
        if (stage == null && matchId != null) {
            TMatch matchById = matchMapper.selectById(matchId);
            if (matchById != null) {
                stage = stages.stream().filter(s -> s.getId().equals(matchById.getStageId())).findFirst().orElse(null);
            }
        }
        if (stage == null) {
            stage = stages.get(0);
        }
        // 结果公布模式:DIRECTOR 由导播台判定,裁判无需判罚
        RuleConfigHolder stageRc = RuleConfigParser.parse(stage.getRuleConfig());
        // 淘汰赛读 knockout.publishMode,擂台赛等读顶层 publishMode(统一在 RuleConfigHolder)
        String publishMode = stageRc == null ? "AUTO" : stageRc.resolvePublishMode();
        String publishScope = stageRc != null ? stageRc.getPublishScope() : null;
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        boolean isAuditionStage = profile.audition();
        boolean isRankStage = profile.rank();
        boolean perCompetitorStage = profile.result().perCompetitor();
        MatchFormat matchFormat = MatchFormat.of(stage.getRuleConfig());
        // 排名赛 MANUAL/BATCH:公布前隐藏汇总分/排名,裁判仍可见自己的打分
        boolean rankResultHidden = isRankStage && !"AUTO".equalsIgnoreCase(publishMode);
        // 分圈海选:裁判只应看到/判罚自己绑定的圈(t_match_referee 圈级绑定)。
        // 只有该赛段确实做了圈级绑定时才启用过滤,单圈/未配置圈裁判的旧数据保持原行为。
        Set<Long> allowedMatchIds = allowedMatchIds(stage.getId(), refereeId);
        List<TMatch> matches = new ArrayList<>();
        if (!"DIRECTOR".equalsIgnoreCase(publishMode)) {
            matches = matchMapper.selectList(
                Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stage.getId())
                    .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
            if (allowedMatchIds != null) {
                // Stream.toList() 不可变,后面还要 sort,这里包一层可变列表
                matches = new ArrayList<>(matches.stream()
                    .filter(m -> allowedMatchIds.contains(m.getId()))
                    .toList());
            }
        }
        // 场次按 id 排序,保证选择稳定
        matches.sort(Comparator.comparing(TMatch::getId));
        TMatch match = null;
        List<TMatchParticipant> participants = new ArrayList<>();
        if (!matches.isEmpty()) {
            match = matches.stream()
                .filter(m -> matchId != null && m.getId().equals(matchId))
                .findFirst()
                .orElse(matches.get(0));
            // 候选场次的参赛方一次批量取回后按场次分组:此前当前场次无参赛方时逐场回查
            List<Long> candidateMatchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
            Map<Long, List<TMatchParticipant>> partsByMatch = candidateMatchIds.isEmpty() ? Map.of()
                : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                        .in(TMatchParticipant::getMatchId, candidateMatchIds))
                    .stream()
                    .filter(p -> p.getMatchId() != null)
                    .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
            participants = new ArrayList<>(partsByMatch.getOrDefault(match.getId(), List.of()));
            // 当前场次无参赛方时,自动切到有参赛方的进行中场次(避免"暂无参赛信息")
            if (participants.isEmpty()) {
                for (TMatch alt : matches) {
                    if (alt.getId().equals(match.getId())) {
                        continue;
                    }
                    List<TMatchParticipant> altParts = partsByMatch.getOrDefault(alt.getId(), List.of());
                    if (!altParts.isEmpty()) {
                        match = alt;
                        participants = new ArrayList<>(altParts);
                        break;
                    }
                }
            }
        }
        // 海选/排名赛:号码即上场顺序,直接按号码数值排序,
        // 避免补签选手按插入顺序排在队尾(不依赖可能错位的 displaySlotIndex)
        if (perCompetitorStage && !participants.isEmpty()) {
            List<Long> pids = participants.stream()
                .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
            Map<Long, String> numberById = pids.isEmpty() ? Map.of()
                : competitorMapper.selectByIds(pids).stream()
                    .collect(Collectors.toMap(TCompetitor::getId,
                        c -> c.getNumber() == null ? "" : c.getNumber(), (a, b) -> a));
            participants.sort(Comparator
                .comparingInt((TMatchParticipant p) -> {
                    String n = p.getCompetitorId() == null ? ""
                        : numberById.getOrDefault(p.getCompetitorId(), "");
                    return parseNumber(n);
                })
                .thenComparing(p -> p.getDisplaySlotIndex() == null ? Long.MAX_VALUE : p.getDisplaySlotIndex()));
        }
        List<TMatchRound> rounds = match == null ? List.of() : matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, match.getId())
                .orderByAsc(TMatchRound::getRoundSequence));

        RefereeMatchVo vo = new RefereeMatchVo();
        vo.setRefereeId(refereeId);
        vo.setRefereeName(refereeName);
        vo.setTournamentId(tournamentId);
        vo.setPublishMode(publishMode);
        vo.setPublishScope(publishScope);
        // BO 局分:淘汰赛(非逐选手赛制)展示「第几局 + 已赢局数 + 是否待提前确认」
        if (!perCompetitorStage) {
            vo.setBestOf(matchFormat.maxGames());
            vo.setRequiredWins(matchFormat.requiredWins());
            // 局分不加字段:从每局判罚明细(t_round_score 的 VOTE)现算,左/右按场次槽位 0/1
            Long leftCid = null;
            Long rightCid = null;
            for (TMatchParticipant p : participants) {
                if (p.getDisplaySlotIndex() == null) {
                    continue;
                }
                if (p.getDisplaySlotIndex() == 0L) {
                    leftCid = p.getCompetitorId();
                } else if (p.getDisplaySlotIndex() == 1L) {
                    rightCid = p.getCompetitorId();
                }
            }
            int leftWins = 0;
            int rightWins = 0;
            for (TMatchRound r : rounds) {
                // 只有整局判完(所有应到裁判都判了)才计局分
                if (!StageConstants.MATCH_SETTLED.equals(r.getStatus())) {
                    continue;
                }
                int lw = roundWinVotes(r.getId(), leftCid);
                int rw = roundWinVotes(r.getId(), rightCid);
                if (lw > rw) {
                    leftWins++;
                } else if (rw > lw) {
                    rightWins++;
                }
            }
            vo.setSeriesLeftWins(leftWins);
            vo.setSeriesRightWins(rightWins);
            vo.setSeriesWinnerSide(leftWins >= matchFormat.requiredWins() ? "LEFT"
                : rightWins >= matchFormat.requiredWins() ? "RIGHT" : null);
            TMatchRound lastRound = rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
            vo.setCurrentRoundGaming(lastRound != null
                && StageConstants.MATCH_GAMING.equals(lastRound.getStatus()));
            vo.setCurrentGame(Math.max(1, rounds.size()));
        }
        // 海选:裁判端要显示「本场晋级几人 / 本赛段共晋级几人」(加赛显示本次加赛争几个名额)
        if (isAuditionStage) {
            vo.setStageAdvanceCount(auditionAdvanceInfoSupport.stageAdvanceCount(stage));
            if (match != null) {
                vo.setAdvanceCount(auditionAdvanceInfoSupport.matchAdvanceCount(stage, match));
                vo.setTiebreakerRound(auditionAdvanceInfoSupport.tiebreakerRoundName(match));
            }
        }

        // 打分配置:决定裁判端界面形态(胜平负 / 总分 / 多维度)
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc != null && rc.getScoring() != null) {
            vo.setScoreType(rc.getScoring().getType());
            if (rc.getScoring().getDimensions() != null) {
                List<RefereeMatchVo.RefereeDimensionInfo> dims = new ArrayList<>();
                for (var d : rc.getScoring().getDimensions()) {
                    RefereeMatchVo.RefereeDimensionInfo di = new RefereeMatchVo.RefereeDimensionInfo();
                    di.setKey(d.getKey());
                    di.setName(d.getName());
                    di.setWeight(d.getWeight());
                    di.setMaxScore(d.getMaxScore());
                    dims.add(di);
                }
                vo.setDimensions(dims);
            }
        }
        // 海选赛满分下发:按赛段 ruleConfig.maxScore 切换百分制/十分制(未配置默认 10 分制)
        if (isAuditionStage) {
            vo.setMaxScore(rc != null && rc.getMaxScore() != null
                ? rc.getMaxScore() : java.math.BigDecimal.valueOf(10));
        }

        RefereeStageInfo stageInfo = new RefereeStageInfo();
        stageInfo.setId(stage.getId());
        stageInfo.setName(stage.getName());
        stageInfo.setStageMode(stage.getStageMode());
        stageInfo.setStatus(stage.getStatus());
        vo.setStage(stageInfo);

        // 裁判名下所有进行中赛段(前端自动跟随/手动切换)
        List<RefereeStageInfo> stageInfos = new ArrayList<>();
        for (TStage s : stages) {
            RefereeStageInfo si = new RefereeStageInfo();
            si.setId(s.getId());
            si.setName(s.getName());
            si.setStageMode(s.getStageMode());
            si.setStatus(s.getStatus());
            stageInfos.add(si);
        }
        vo.setStages(stageInfos);

        if (match != null) {
            RefereeMatchInfo matchInfo = new RefereeMatchInfo();
            matchInfo.setId(match.getId());
            matchInfo.setName(match.getName());
            matchInfo.setStatus(match.getStatus());
            matchInfo.setMatchMode(match.getMatchMode());
            vo.setMatch(matchInfo);
            vo.setPendingPublish(StringUtils.isNotBlank(match.getResultJson()));
        }

        // 赛段内所有进行中场次(多场并行时供裁判切换)
        List<RefereeMatchInfo> matchInfos = new ArrayList<>();
        for (TMatch m : matches) {
            RefereeMatchInfo mi = new RefereeMatchInfo();
            mi.setId(m.getId());
            mi.setName(m.getName());
            mi.setStatus(m.getStatus());
            mi.setMatchMode(m.getMatchMode());
            matchInfos.add(mi);
        }
        vo.setMatches(matchInfos);

        // 当前赛段全部场次(顶部横向列表 + 赛段总览,含每场轮次结果)
        List<TMatch> stageAllMatches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getDisplayCol)
                .orderByAsc(TMatch::getId));
        // 分圈:只展示本裁判绑定的圈
        if (allowedMatchIds != null) {
            stageAllMatches = new ArrayList<>(stageAllMatches.stream()
                .filter(m -> allowedMatchIds.contains(m.getId()))
                .toList());
        }
        List<RefereeMatchInfo> stageMatchInfos = new ArrayList<>();
        List<RefereeMatchVo.RefereeStageMatchInfo> stageOverview = new ArrayList<>();
        List<TMatchRound> allRounds = List.of();
        List<TMatchParticipant> allParts = List.of();
        Map<Long, String> competitorNameById = Map.of();
        if (!stageAllMatches.isEmpty()) {
            List<Long> stageMatchIds = stageAllMatches.stream().map(TMatch::getId).toList();
            allRounds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, stageMatchIds)
                    .orderByAsc(TMatchRound::getMatchId)
                    .orderByAsc(TMatchRound::getRoundSequence));
            allParts = participantMapper.selectList(
                Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, stageMatchIds)
                    .orderByAsc(TMatchParticipant::getMatchId)
                    .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            List<Long> cids = allParts.stream()
                .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
            competitorNameById = cids.isEmpty() ? Map.of()
                : competitorMapper.selectByIds(cids).stream()
                    .collect(java.util.stream.Collectors.toMap(TCompetitor::getId, TCompetitor::getName, (a, b) -> a));
        }
        Map<Long, List<TMatchRound>> roundsByMatch = allRounds.stream()
            .collect(java.util.stream.Collectors.groupingBy(TMatchRound::getMatchId));
        Map<Long, List<TMatchParticipant>> partsByMatch = allParts.stream()
            .collect(java.util.stream.Collectors.groupingBy(TMatchParticipant::getMatchId));
        for (TMatch m : stageAllMatches) {
            RefereeMatchInfo mi = new RefereeMatchInfo();
            mi.setId(m.getId());
            mi.setName(m.getName());
            mi.setStatus(m.getStatus());
            mi.setMatchMode(m.getMatchMode());
            stageMatchInfos.add(mi);

            RefereeMatchVo.RefereeStageMatchInfo sm = new RefereeMatchVo.RefereeStageMatchInfo();
            sm.setId(m.getId());
            sm.setName(m.getName());
            sm.setStatus(m.getStatus());
            sm.setMatchMode(m.getMatchMode());
            List<TMatchRound> mr = roundsByMatch.getOrDefault(m.getId(), List.of());
            List<RefereeRoundInfo> ris = new ArrayList<>();
            for (int i = 0; i < mr.size(); i++) {
                TMatchRound r = mr.get(i);
                RefereeRoundInfo ri = new RefereeRoundInfo();
                ri.setId(r.getId());
                ri.setRoundSequence(r.getRoundSequence());
                ri.setStatus(r.getStatus());
                // BO1 平局加赛轮:已结算且非最后一轮即平局局。BO3/BO5 有胜局也会产生后续局,
                // 不能据此判平——局分以整场 seriesLeftWins/seriesRightWins 为准,这里不再标注每局胜平。
                if (!perCompetitorStage && matchFormat.maxGames() <= 1
                    && StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < mr.size() - 1) {
                    ri.setOutcome("DRAW");
                }
                ris.add(ri);
            }
            sm.setRounds(ris);

            String winnerName = null;
            List<RefereeMatchVo.RefereeStageParticipantInfo> sps = new ArrayList<>();
            for (TMatchParticipant p : partsByMatch.getOrDefault(m.getId(), List.of())) {
                RefereeMatchVo.RefereeStageParticipantInfo spi = new RefereeMatchVo.RefereeStageParticipantInfo();
                spi.setCompetitorId(p.getCompetitorId());
                spi.setCompetitorName(p.getCompetitorId() == null ? null : competitorNameById.get(p.getCompetitorId()));
                spi.setOutcomeStatus(p.getOutcomeStatus());
                sps.add(spi);
                // 海选是"逐选手打分 + 按名次晋级/淘汰",没有对阵胜负,也就没有"胜者"。
                // 名次第一只是分数最高:打「胜者」标签会让裁判以为海选也决出了冠军。
                boolean isWinner = !rankResultHidden && !isAuditionStage && ("WIN".equals(p.getOutcomeStatus())
                    || (p.getRankInMatch() != null && p.getRankInMatch() == 1 && p.getScoreValue() != null));
                if (isWinner && p.getCompetitorId() != null) {
                    winnerName = competitorNameById.get(p.getCompetitorId());
                }
            }
            sm.setParticipants(sps.isEmpty() ? null : sps);
            sm.setWinnerName(winnerName);
            stageOverview.add(sm);
        }
        vo.setStageMatches(stageMatchInfos);
        vo.setStageOverview(stageOverview);

        // 当前生效轮:淘汰赛/擂台赛取最新一轮(平局加赛/多轮制时即当前判罚轮);
        // 海选/排名赛逐选手轮次无平局语义,取第一轮作为展示轮
        TMatchRound currentRound = rounds.isEmpty() ? null
            : perCompetitorStage ? rounds.get(0) : rounds.get(rounds.size() - 1);
        if (currentRound != null) {
            RefereeRoundInfo roundInfo = new RefereeRoundInfo();
            roundInfo.setId(currentRound.getId());
            roundInfo.setRoundSequence(currentRound.getRoundSequence());
            roundInfo.setStatus(currentRound.getStatus());
            vo.setCurrentRound(roundInfo);
        }
        // 本场全部轮次(多轮制时前端按轮次切换)
        List<RefereeRoundInfo> roundInfos = new ArrayList<>();
        for (int i = 0; i < rounds.size(); i++) {
            TMatchRound r = rounds.get(i);
            RefereeRoundInfo ri = new RefereeRoundInfo();
            ri.setId(r.getId());
            ri.setRoundSequence(r.getRoundSequence());
            ri.setStatus(r.getStatus());
            // BO1 平局加赛轮才把"已结算且非最后一轮"判为平局;BO3/BO5 胜局也会有后续局。
            if (!perCompetitorStage && matchFormat.maxGames() <= 1
                && StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < rounds.size() - 1) {
                ri.setOutcome("DRAW");
            }
            roundInfos.add(ri);
        }
        vo.setRounds(roundInfos);

        // 多裁判判罚进度(STANDARD 淘汰赛):已投票/应投票
        if (match != null && currentRound != null
            && !perCompetitorStage
            && "STANDARD".equals(match.getMatchMode())) {
            int assigned = refereeStageService.getRefereeIdsByStageId(stage.getId()).size();
            if (assigned > 1) {
                long voted = roundScoreMapper.selectList(
                        Wrappers.<TRoundScore>lambdaQuery()
                            .eq(TRoundScore::getRoundId, currentRound.getId())
                            .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE)
                            .select(TRoundScore::getRefereeId))
                    .stream().map(TRoundScore::getRefereeId).filter(Objects::nonNull).distinct().count();
                vo.setVoteProgress(voted + "/" + assigned);
            }
        }

        List<RefereeParticipantInfo> partInfos = new ArrayList<>();
        List<RefereeMatchVo.RefereeScoreInfo> myScoreInfos = new ArrayList<>();
        // 查询当前裁判对各参赛方的已有打分
        Map<Long, java.math.BigDecimal> refereeScores = new HashMap<>();
        List<Long> perCompetitorRoundIds = (match != null && perCompetitorStage)
            ? matchRoundMapper.selectList(
                    Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList()
            : List.of();
        List<TRoundScore> myScores;
        if (match != null && perCompetitorStage) {
            // 海选赛/排名赛逐选手打分分布在本场各回合,跨回合聚合该裁判的打分
            myScores = perCompetitorRoundIds.isEmpty() ? List.of() : roundScoreMapper.selectList(
                Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, perCompetitorRoundIds)
                    .eq(TRoundScore::getRefereeId, refereeId));
        } else if (match != null && currentRound != null) {
            myScores = roundScoreMapper.selectList(
                Wrappers.<TRoundScore>lambdaQuery()
                    .eq(TRoundScore::getRoundId, currentRound.getId())
                    .eq(TRoundScore::getRefereeId, refereeId));
        } else {
            myScores = List.of();
        }
        if (!myScores.isEmpty()) {
            for (TRoundScore rs : myScores) {
                if (rs.getCompetitorId() != null && rs.getScore() != null) {
                    refereeScores.merge(rs.getCompetitorId(), rs.getScore(), java.math.BigDecimal::add);
                    RefereeMatchVo.RefereeScoreInfo si = new RefereeMatchVo.RefereeScoreInfo();
                    si.setCompetitorId(rs.getCompetitorId());
                    si.setDimension(rs.getDimension());
                    si.setScore(rs.getScore());
                    myScoreInfos.add(si);
                }
            }
        }
        vo.setMyScores(myScoreInfos);
        // 海选:当前累计总分一律按打分明细现算。participant.score_value 是提交时刷新的缓存,
        // 并发提交/一场残留多回合时可能只含部分裁判 —— 显示层不能拿它当总分口径。
        Map<Long, java.math.BigDecimal> globalScoreByComp = new HashMap<>();
        if (isAuditionStage && !perCompetitorRoundIds.isEmpty()) {
            roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, perCompetitorRoundIds)
                    .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_SCORE)
                    .select(TRoundScore::getCompetitorId, TRoundScore::getScore))
                .forEach(s -> {
                    if (s.getCompetitorId() != null && s.getScore() != null) {
                        globalScoreByComp.merge(s.getCompetitorId(), s.getScore(), java.math.BigDecimal::add);
                    }
                });
        }
        // 参赛方姓名/号码批量取:此前循环内逐个 selectById,36 人的海选圈一次刷新就是 36 条 SQL
        List<Long> partCompetitorIds = participants.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> partCompetitors = partCompetitorIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(partCompetitorIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        for (TMatchParticipant p : participants) {
            RefereeParticipantInfo pi = new RefereeParticipantInfo();
            pi.setCompetitorId(p.getCompetitorId());
            pi.setDisplaySlotIndex(p.getDisplaySlotIndex());
            // 排名赛未公布时隐藏汇总分与排名,避免裁判端提前看到全局结果
            pi.setCurrentScore(rankResultHidden ? null
                : (isAuditionStage ? globalScoreByComp.get(p.getCompetitorId()) : p.getScoreValue()));
            pi.setRankInMatch(rankResultHidden ? null : p.getRankInMatch());
            if (p.getCompetitorId() != null && refereeScores.containsKey(p.getCompetitorId())) {
                pi.setMyScore(refereeScores.get(p.getCompetitorId()));
            }
            if (p.getCompetitorId() != null) {
                TCompetitor comp = partCompetitors.get(p.getCompetitorId());
                pi.setCompetitorName(comp != null ? comp.getName() : ("选手 " + p.getCompetitorId()));
                pi.setNumber(comp != null ? comp.getNumber() : null);
            } else {
                pi.setCompetitorName(StageConstants.SLOT_BYE.equals(p.getSlotKind()) ? "轮空" : "待定");
            }
            partInfos.add(pi);
        }
        vo.setParticipants(partInfos);
        return vo;
    }

    @Override
    public MatchResultVo submitScore(Long matchId, Long tournamentId, Long refereeId, String refereeName,
                                     SubmitResultBo bo) {
        bo.setMatchId(matchId);
        bo.setRefereeId(refereeId);

        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (!StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            throw new ServiceException("场次状态不允许提交判罚");
        }
        // 越权防护:裁判只能提交本人所在赛事的场次,且必须已被分配该赛段/该圈
        if (tournamentId != null && !tournamentId.equals(match.getTournamentId())) {
            throw new ServiceException("无权提交该场次的判罚");
        }
        assertScoringPermission(match.getStageId(), matchId, refereeId);

        MatchResultVo result = matchResultService.submitResult(bo);
        log.info("裁判[{}](id={})提交场次[{}]判罚结果", refereeName, refereeId, matchId);
        return result;
    }

    @Override
    public List<RefereeParticipantInfo> matchScores(Long matchId, Long tournamentId, Long refereeId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        if (tournamentId != null && !tournamentId.equals(match.getTournamentId())) {
            throw new ServiceException("无权查看该场次");
        }
        assertScoringPermission(match.getStageId(), matchId, refereeId);
        // 排名赛未公布时隐藏汇总分/排名(与 my-match 同一口径),避免轻量接口提前泄露
        TStage stage = stageMapper.selectById(match.getStageId());
        boolean rankResultHidden = false;
        if (stage != null && StageModeProfiles.of(stage.getStageMode()).rank()
            && !StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
            rankResultHidden = rc != null && StringUtils.isNotBlank(rc.getPublishMode())
                && !"AUTO".equalsIgnoreCase(rc.getPublishMode());
        }
        List<TMatchParticipant> participants = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        // 海选:当前累计总分按打分明细现算(缓存 score_value 可能只含部分裁判)
        boolean isAudition = stage != null && StageModeProfiles.of(stage.getStageMode()).audition();
        Map<Long, java.math.BigDecimal> globalScoreByComp = new HashMap<>();
        if (isAudition) {
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .eq(TMatchRound::getMatchId, matchId).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                        .in(TRoundScore::getRoundId, roundIds)
                        .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_SCORE)
                        .select(TRoundScore::getCompetitorId, TRoundScore::getScore))
                    .forEach(s -> {
                        if (s.getCompetitorId() != null && s.getScore() != null) {
                            globalScoreByComp.merge(s.getCompetitorId(), s.getScore(), java.math.BigDecimal::add);
                        }
                    });
            }
        }
        List<RefereeParticipantInfo> out = new ArrayList<>();
        for (TMatchParticipant p : participants) {
            if (p.getCompetitorId() == null) {
                continue;
            }
            RefereeParticipantInfo pi = new RefereeParticipantInfo();
            pi.setCompetitorId(p.getCompetitorId());
            pi.setDisplaySlotIndex(p.getDisplaySlotIndex());
            pi.setCurrentScore(rankResultHidden ? null
                : (isAudition ? globalScoreByComp.get(p.getCompetitorId()) : p.getScoreValue()));
            pi.setRankInMatch(rankResultHidden ? null : p.getRankInMatch());
            out.add(pi);
        }
        return out;
    }

    /**
     * 一次算清"裁判能不能判这一场":赛段级分配 + 圈级绑定。
     * 合并成 3 条查询(场次 / 场次裁判绑定 / 赛段分配),
     * 替代此前 getStageIdsByRefereeId(3 条)+ allowedMatchIds(2 条)共 5 条。
     */
    private void assertScoringPermission(Long stageId, Long matchId, Long refereeId) {
        List<TMatch> stageMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .select(TMatch::getId, TMatch::getDisplayZone));
        List<Long> stageMatchIds = stageMatches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        List<TMatchReferee> bindings = stageMatchIds.isEmpty() ? List.of()
            : matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .in(TMatchReferee::getMatchId, stageMatchIds)
                .select(TMatchReferee::getMatchId, TMatchReferee::getRefereeId));
        Set<Long> mine = bindings.stream()
            .filter(r -> Objects.equals(r.getRefereeId(), refereeId))
            .map(TMatchReferee::getMatchId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        boolean stageAssigned = refereeStageMapper.selectCount(Wrappers.<TRefereeStage>lambdaQuery()
            .eq(TRefereeStage::getStageId, stageId)
            .eq(TRefereeStage::getRefereeId, refereeId)) > 0;
        if (!stageAssigned && mine.isEmpty()) {
            throw new ServiceException("未分配该赛段的判罚权限");
        }
        // 存在圈级绑定:只能判自己绑定的圈(同圈加赛场次整体可见)
        if (!bindings.isEmpty() && !mine.contains(matchId)) {
            Set<String> myZones = stageMatches.stream()
                .filter(m -> mine.contains(m.getId()) && StringUtils.isNotBlank(m.getDisplayZone()))
                .map(TMatch::getDisplayZone)
                .collect(Collectors.toSet());
            boolean allowed = stageMatches.stream().anyMatch(m -> Objects.equals(m.getId(), matchId)
                && StringUtils.isNotBlank(m.getDisplayZone()) && myZones.contains(m.getDisplayZone()));
            if (!allowed) {
                throw new ServiceException("未分配该圈(场次)的判罚权限");
            }
        }
    }

    /** 参赛号码转数值用于排序:空/非数字号码排最后 */
    private static int parseNumber(String number) {
        if (number == null || number.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 本赛段内该裁判可判罚的场次(圈)。
     *
     * <p>分圈海选会把裁判按圈绑定到 t_match_referee。只要本赛段存在圈级绑定,
     * 裁判就只能看到并判罚自己绑定的圈;赛段未做圈级绑定时返回 null 表示不限制。</p>
     *
     * @return null = 不限制;否则返回允许的场次ID集合(可能为空集合 = 未分配到任何圈)
     */
    private Set<Long> allowedMatchIds(Long stageId, Long refereeId) {
        List<TMatch> stageMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .select(TMatch::getId, TMatch::getDisplayZone));
        if (stageMatches.isEmpty()) {
            return null;
        }
        List<Long> stageMatchIds = stageMatches.stream().map(TMatch::getId).toList();
        List<TMatchReferee> stageBindings = matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
            .in(TMatchReferee::getMatchId, stageMatchIds)
            .select(TMatchReferee::getMatchId, TMatchReferee::getRefereeId));
        if (stageBindings.isEmpty()) {
            // 没有圈级绑定(单圈海选/未配置圈裁判):不限制,保持原行为
            return null;
        }
        Set<Long> mine = new LinkedHashSet<>();
        for (TMatchReferee r : stageBindings) {
            if (Objects.equals(r.getRefereeId(), refereeId)) {
                mine.add(r.getMatchId());
            }
        }
        // 绑定的场次所在圈整体可见:二海(同分加赛)场次是结算时新建的、按圈归属原圈,
        // 只要裁判绑定了该圈的任一场次即可判罚该圈后续新增的加赛场次。
        Set<String> myZones = stageMatches.stream()
            .filter(m -> mine.contains(m.getId()) && org.apache.commons.lang3.StringUtils.isNotBlank(m.getDisplayZone()))
            .map(TMatch::getDisplayZone)
            .collect(Collectors.toSet());
        Set<Long> allowed = new LinkedHashSet<>();
        for (TMatch m : stageMatches) {
            boolean sameZone = org.apache.commons.lang3.StringUtils.isNotBlank(m.getDisplayZone())
                && myZones.contains(m.getDisplayZone());
            if (mine.contains(m.getId()) || sameZone) {
                allowed.add(m.getId());
            }
        }
        return allowed;
    }

    /** 某局判某参赛方胜的裁判票数(VOTE 明细里 score=1 且指向该参赛方的行数) */
    private int roundWinVotes(Long roundId, Long competitorId) {
        if (roundId == null || competitorId == null) {
            return 0;
        }
        return roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
            .eq(TRoundScore::getRoundId, roundId)
            .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE)
            .eq(TRoundScore::getCompetitorId, competitorId)
            .eq(TRoundScore::getScore, java.math.BigDecimal.ONE)).intValue();
    }
}
