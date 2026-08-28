package com.dance.street.game.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.SubmitResultBo;
import com.dance.street.game.domain.vo.MatchResultVo;
import com.dance.street.game.domain.vo.RefereeMatchVo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeMatchInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeParticipantInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeRoundInfo;
import com.dance.street.game.domain.vo.RefereeMatchVo.RefereeStageInfo;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.interceptor.RefereeAuthInterceptor;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchResultService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.ITStageLifecycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 裁判端接口：authKey 即认证凭证，由 RefereeAuthInterceptor 校验
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/game/referee-match")
public class RefereeMatchController {

    private final HttpServletRequest request;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final ITMatchResultService matchResultService;
    private final ITRefereeStageService refereeStageService;
    private final ITStageLifecycleService stageLifecycleService;
    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;

    /**
     * 裁判端 SSE 长连接:赛段/场次/打分变化时实时推送刷新
     */
    @GetMapping("/sse")
    public SseEmitter sse() {
        TRefereeVo referee = (TRefereeVo) request.getAttribute(RefereeAuthInterceptor.REFEREE_ATTR);
        return tournamentEventSseEmitterManager.connectReferee(referee.getTournamentId(), referee.getId());
    }

    /**
     * 获取裁判对应的当前比赛信息
     * 根据当前进行的 赛段/场次 自动定位:未指定时取裁判名下第一个进行中赛段的第一场进行中场次。
     * 多赛段/多场并行时前端可通过 stageId / matchId 显式切换。
     */
    @GetMapping("/my-match")
    public R<RefereeMatchVo> getMyMatch(@RequestParam(required = false) Long stageId,
                                        @RequestParam(required = false) Long matchId) {
        TRefereeVo referee = (TRefereeVo) request.getAttribute(RefereeAuthInterceptor.REFEREE_ATTR);
        Long tournamentId = referee.getTournamentId();

        List<Long> stageIds = refereeStageService.getStageIdsByRefereeId(referee.getId());
        if (stageIds.isEmpty()) {
            return R.fail("暂未分配赛段，请联系管理员");
        }

        List<TStage> stages = stageMapper.selectList(
            Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, tournamentId)
                .in(TStage::getId, stageIds)
                .eq(TStage::getStatus, StageConstants.STAGE_GAMING)
                .orderByAsc(TStage::getId));
        if (stages.isEmpty()) {
            return R.fail("当前没有进行中的赛段");
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
        String publishMode = "AUTO";
        if (stageRc != null && stageRc.getKnockout() != null
            && StringUtils.isNotBlank(stageRc.getKnockout().getPublishMode())) {
            publishMode = stageRc.getKnockout().getPublishMode();
        } else if (stageRc != null && StringUtils.isNotBlank(stageRc.getPublishMode())) {
            publishMode = stageRc.getPublishMode();
        }
        String publishScope = stageRc != null ? stageRc.getPublishScope() : null;
        boolean isRankStage = StageModeEnum.RANK.getCode().equals(stage.getStageMode());
        boolean perCompetitorStage = StageModeEnum.AUDITION.getCode().equals(stage.getStageMode()) || isRankStage;
        // 排名赛 MANUAL/BATCH:公布前隐藏汇总分/排名,裁判仍可见自己的打分
        boolean rankResultHidden = isRankStage && !"AUTO".equalsIgnoreCase(publishMode);
        List<TMatch> matches = new ArrayList<>();
        if (!"DIRECTOR".equalsIgnoreCase(publishMode)) {
            matches = matchMapper.selectList(
                Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stage.getId())
                    .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
        }
        // 海选赛/排名赛兜底：若赛段已开始但无场次，自动生成
        if (matches.isEmpty() && perCompetitorStage) {
            long total = matchMapper.selectCount(
                Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
            if (total == 0) {
                GenerateMatchesBo gm = new GenerateMatchesBo();
                gm.setStageId(stage.getId());
                stageLifecycleService.generateMatches(gm);
                // 生成后把场次改为 GAMING
                TMatch upd = new TMatch();
                upd.setStatus(StageConstants.MATCH_GAMING);
                matchMapper.update(upd, Wrappers.<TMatch>lambdaUpdate().eq(TMatch::getStageId, stage.getId()));
                TMatchRound roundUpd = new TMatchRound();
                roundUpd.setStatus(StageConstants.MATCH_GAMING);
                List<Long> autoMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                        .eq(TMatch::getStageId, stage.getId()).select(TMatch::getId))
                    .stream().map(TMatch::getId).toList();
                if (!autoMatchIds.isEmpty()) {
                    matchRoundMapper.update(roundUpd, Wrappers.<TMatchRound>lambdaUpdate()
                        .in(TMatchRound::getMatchId, autoMatchIds));
                }
                log.info("海选赛赛段[{}]自动补救生成对阵", stage.getId());
            }
            matches = matchMapper.selectList(
                Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stage.getId())
                    .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
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
            participants = participantMapper.selectList(
                Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, match.getId()));
            // 当前场次无参赛方时,自动切到有参赛方的进行中场次(避免"暂无参赛信息")
            if (participants.isEmpty()) {
                for (TMatch alt : matches) {
                    if (alt.getId().equals(match.getId())) {
                        continue;
                    }
                    List<TMatchParticipant> altParts = participantMapper.selectList(
                        Wrappers.<TMatchParticipant>lambdaQuery()
                            .eq(TMatchParticipant::getMatchId, alt.getId()));
                    if (!altParts.isEmpty()) {
                        match = alt;
                        participants = altParts;
                        break;
                    }
                }
            }
        }
        List<TMatchRound> rounds = match == null ? List.of() : matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, match.getId())
                .orderByAsc(TMatchRound::getRoundSequence));

        RefereeMatchVo vo = new RefereeMatchVo();
        vo.setRefereeId(referee.getId());
        vo.setRefereeName(referee.getName());
        vo.setTournamentId(tournamentId);
        vo.setPublishMode(publishMode);
        vo.setPublishScope(publishScope);

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
                // 平局加赛轮仅存在于淘汰赛/擂台赛(胜负判罚产生后续轮次);
                // 海选/排名赛逐选手各占一个轮次,不标记为平局
                if (!perCompetitorStage && StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < mr.size() - 1) {
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
                boolean isWinner = !rankResultHidden && ("WIN".equals(p.getOutcomeStatus())
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
            // 已结算且非最后一轮的轮次 = 平局加赛轮(只有平局才会产生后续轮次);
            // 海选/排名赛逐选手各占一个轮次,不标记为平局
            if (!perCompetitorStage && StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < rounds.size() - 1) {
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
        Map<Long, java.math.BigDecimal> refereeScores = new java.util.HashMap<>();
        List<TRoundScore> myScores;
        if (perCompetitorStage) {
            // 海选赛/排名赛逐选手打分分布在各自轮次,跨本场全部轮次聚合该裁判的打分
            List<Long> roundIds = matchRoundMapper.selectList(
                    Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, match.getId()).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            myScores = roundIds.isEmpty() ? List.of() : roundScoreMapper.selectList(
                Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds)
                    .eq(TRoundScore::getRefereeId, referee.getId()));
        } else if (currentRound != null) {
            myScores = roundScoreMapper.selectList(
                Wrappers.<TRoundScore>lambdaQuery()
                    .eq(TRoundScore::getRoundId, currentRound.getId())
                    .eq(TRoundScore::getRefereeId, referee.getId()));
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
        for (TMatchParticipant p : participants) {
            RefereeParticipantInfo pi = new RefereeParticipantInfo();
            pi.setCompetitorId(p.getCompetitorId());
            pi.setDisplaySlotIndex(p.getDisplaySlotIndex());
            // 排名赛未公布时隐藏汇总分与排名,避免裁判端提前看到全局结果
            pi.setCurrentScore(rankResultHidden ? null : p.getScoreValue());
            pi.setRankInMatch(rankResultHidden ? null : p.getRankInMatch());
            if (p.getCompetitorId() != null && refereeScores.containsKey(p.getCompetitorId())) {
                pi.setMyScore(refereeScores.get(p.getCompetitorId()));
            }
            if (p.getCompetitorId() != null) {
                var comp = competitorMapper.selectById(p.getCompetitorId());
                pi.setCompetitorName(comp != null ? comp.getName() : ("选手 " + p.getCompetitorId()));
            } else {
                pi.setCompetitorName("待定");
            }
            partInfos.add(pi);
        }
        vo.setParticipants(partInfos);
        return R.ok(vo);
    }

    /**
     * 裁判提交打分
     */
    @PostMapping("/{matchId}/submit-score")
    public R<MatchResultVo> submitScore(
            @PathVariable Long matchId,
            @RequestBody SubmitResultBo bo) {
        TRefereeVo referee = (TRefereeVo) request.getAttribute(RefereeAuthInterceptor.REFEREE_ATTR);

        bo.setMatchId(matchId);
        bo.setRefereeId(referee.getId());

        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            return R.fail("场次不存在");
        }
        if (!StageConstants.MATCH_GAMING.equals(match.getStatus())) {
            return R.fail("场次状态不允许提交判罚");
        }
        // 越权防护:裁判只能提交本人所在赛事的场次,且必须已被分配该赛段
        if (referee.getTournamentId() != null && !referee.getTournamentId().equals(match.getTournamentId())) {
            return R.fail("无权提交该场次的判罚");
        }
        if (!refereeStageService.getStageIdsByRefereeId(referee.getId()).contains(match.getStageId())) {
            return R.fail("未分配该赛段的判罚权限");
        }

        MatchResultVo result = matchResultService.submitResult(bo);
        log.info("裁判[{}](id={})提交场次[{}]判罚结果", referee.getName(), referee.getId(), matchId);
        return R.ok(result);
    }
}
