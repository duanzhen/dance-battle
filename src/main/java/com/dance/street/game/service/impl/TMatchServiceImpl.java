package com.dance.street.game.service.impl;

import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dance.street.game.domain.bo.TMatchBo;
import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.vo.MatchRoundScoreVo;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.impl.settle.AuditionAdvanceInfoSupport;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfiles;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.MatchFormat;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 比赛场次Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TMatchServiceImpl implements ITMatchService {

    private final TMatchMapper baseMapper;
    private final TMatchParticipantMapper matchParticipantMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TRefereeMapper refereeMapper;
    private final TStageMapper stageMapper;
    private final ITRefereeStageService refereeStageService;
    /** 海选晋级人数展示口径(与结算同一套) */
    private final AuditionAdvanceInfoSupport auditionAdvanceInfoSupport;

    /**
     * 查询比赛场次
     *
     * @param id 主键
     * @return 比赛场次
     */
    @Override
    public TMatchVo queryById(Long id){
        TMatchVo vo = baseMapper.selectVoById(id);
        // 与列表一致:补齐左右方名称/胜负、裁判判罚(refereeVotes)、各轮判罚明细(roundVotes),
        // 供大屏「当前场次」等实时展示每个裁判的红蓝判罚与最终结果
        if (vo != null) {
            fillMatchNames(List.of(vo));
        }
        return vo;
    }

    /**
     * 分页查询比赛场次列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 比赛场次分页列表
     */
    @Override
    public TableDataInfo<TMatchVo> queryPageList(TMatchBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TMatch> lqw = buildQueryWrapper(bo);
        Page<TMatchVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillMatchNames(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的比赛场次列表
     *
     * @param bo 查询条件
     * @return 比赛场次列表
     */
    @Override
    public List<TMatchVo> queryList(TMatchBo bo) {
        LambdaQueryWrapper<TMatch> lqw = buildQueryWrapper(bo);
        List<TMatchVo> list = baseMapper.selectVoList(lqw);
        fillMatchNames(list);
        return list;
    }

    /**
     * 填充场次左右参赛方名称(按 displaySlotIndex 槽位,0→left、1→right)。
     * 无参赛方(轮空/占位)时保持 null,由前端显示「待定/轮空」。
     */
    private void fillMatchNames(List<TMatchVo> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        List<Long> matchIds = matches.stream().map(TMatchVo::getId).filter(Objects::nonNull).toList();
        if (matchIds.isEmpty()) {
            return;
        }
        List<TMatchParticipant> participants = matchParticipantMapper.selectList(
            Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds)
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        List<Long> competitorIds = participants.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        List<TCompetitor> competitors = competitorIds.isEmpty() ? List.of()
            : competitorMapper.selectByIds(competitorIds);
        Map<Long, String> nameById = competitors.stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c.getName() == null ? "" : c.getName(), (a, b) -> a));
        // 号码牌:导播台在海选赛段要按号喊人/对号(与名字同源,不多查一次)
        Map<Long, String> numberById = competitors.stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c.getNumber() == null ? "" : c.getNumber(), (a, b) -> a));

        Map<Long, List<TMatchParticipant>> byMatch = participants.stream()
            .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        for (TMatchVo vo : matches) {
            List<TMatchParticipant> parts = byMatch.get(vo.getId());
            if (parts == null || parts.isEmpty()) {
                continue;
            }
            String winnerName = null;
            TMatchParticipant left = parts.size() > 0 ? parts.get(0) : null;
            TMatchParticipant right = parts.size() > 1 ? parts.get(1) : null;
            if (left != null) {
                Long cid = left.getCompetitorId();
                vo.setLeftName(cid == null ? null : nameById.get(cid));
                vo.setLeftCompetitorId(cid);
                vo.setLeftSlotKind(left.getSlotKind());
                boolean leftWin = isWinner(left);
                vo.setLeftWin(leftWin);
                if (leftWin) {
                    winnerName = vo.getLeftName();
                }
            }
            if (right != null) {
                Long cid = right.getCompetitorId();
                vo.setRightName(cid == null ? null : nameById.get(cid));
                vo.setRightCompetitorId(cid);
                vo.setRightSlotKind(right.getSlotKind());
                boolean rightWin = isWinner(right);
                vo.setRightWin(rightWin);
                if (rightWin) {
                    winnerName = vo.getRightName();
                }
            }
            vo.setWinnerName(winnerName);
        }

        // 海选赛段:补充每轮(每名选手)的评分明细
        fillAuditionRoundScores(matches, byMatch, nameById, numberById);

        // 公布模式 + 实时判罚投票
        fillPublishInfo(matches, byMatch);
        // 海选:本场(圈/加赛)晋级人数 + 本赛段共晋级人数(导播台展示)
        fillAuditionAdvanceInfo(matches);
        // 淘汰赛:BO 局分(bestOf/需赢局数/当前局/已赢局数),供 MC 与大屏展示、提前确认
        fillSeriesInfo(matches);
        // 淘汰赛:本场各轮判罚明细(每轮参赛者取自 round_score,保底用场次左右位)
        fillKnockoutRoundVotes(matches, byMatch, nameById);
    }

    /**
     * 淘汰赛 BO 局分:bestOf/需赢局数取赛段 format;局分从每局判罚明细现算(不加字段)。
     * 左/右按场次槽位 0/1,胜局 = 该局该方得票多于对方;平局局双方都不计。
     */
    private void fillSeriesInfo(List<TMatchVo> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList();
        if (stageIds.isEmpty()) {
            return;
        }
        Map<Long, TStage> stageById = stageMapper.selectByIds(stageIds).stream()
            .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        List<TMatchVo> koMatches = matches.stream()
            .filter(m -> {
                TStage s = stageById.get(m.getStageId());
                return s != null && StageModeEnum.KNOCKOUT.getCode().equals(s.getStageMode())
                    && "STANDARD".equals(m.getMatchMode());
            })
            .toList();
        if (koMatches.isEmpty()) {
            return;
        }
        List<Long> matchIds = koMatches.stream().map(TMatchVo::getId).filter(Objects::nonNull).toList();
        List<TMatchRound> rounds = matchIds.isEmpty() ? List.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .orderByAsc(TMatchRound::getMatchId)
                .orderByAsc(TMatchRound::getRoundSequence));
        Map<Long, List<TMatchRound>> roundsByMatch = rounds.stream()
            .collect(Collectors.groupingBy(TMatchRound::getMatchId));
        List<Long> roundIds = rounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TRoundScore>> votesByRound = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds)
                    .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE))
                .stream().collect(Collectors.groupingBy(TRoundScore::getRoundId));
        for (TMatchVo vo : koMatches) {
            MatchFormat fmt = MatchFormat.of(stageById.get(vo.getStageId()).getRuleConfig());
            vo.setBestOf(fmt.maxGames());
            vo.setRequiredWins(fmt.requiredWins());
            List<TMatchRound> roundList = roundsByMatch.getOrDefault(vo.getId(), List.of());
            vo.setCurrentGame(Math.max(1, roundList.size()));
            int leftWins = 0;
            int rightWins = 0;
            for (TMatchRound r : roundList) {
                // 只有整局判完(所有应到裁判都判了)才计局分,避免一张票就把局分算进去
                if (!StageConstants.MATCH_SETTLED.equals(r.getStatus())) {
                    continue;
                }
                int lw = 0;
                int rw = 0;
                for (TRoundScore v : votesByRound.getOrDefault(r.getId(), List.of())) {
                    if (v.getScore() == null || v.getScore().compareTo(BigDecimal.ONE) != 0) {
                        continue;
                    }
                    if (Objects.equals(v.getCompetitorId(), vo.getLeftCompetitorId())) {
                        lw++;
                    } else if (Objects.equals(v.getCompetitorId(), vo.getRightCompetitorId())) {
                        rw++;
                    }
                }
                if (lw > rw) {
                    leftWins++;
                } else if (rw > lw) {
                    rightWins++;
                }
            }
            vo.setSeriesLeftWins(leftWins);
            vo.setSeriesRightWins(rightWins);
            // 整场胜负是否已定 + 当前局是否还在进行(提前确认可与"继续判下一局"并存)
            vo.setSeriesWinnerSide(leftWins >= fmt.requiredWins() ? "LEFT"
                : rightWins >= fmt.requiredWins() ? "RIGHT" : null);
            TMatchRound currentRound = roundList.isEmpty() ? null : roundList.get(roundList.size() - 1);
            vo.setCurrentRoundGaming(currentRound != null
                && StageConstants.MATCH_GAMING.equals(currentRound.getStatus()));
        }
    }

    /**
     * 参赛方是否本场胜者:显式判 WIN,或排名第 1 且已有分数。
     */
    private boolean isWinner(TMatchParticipant p) {
        if (p == null || p.getCompetitorId() == null) {
            return false;
        }
        return "WIN".equals(p.getOutcomeStatus())
            || (p.getRankInMatch() != null && p.getRankInMatch() == 1 && p.getScoreValue() != null);
    }

    /**
     * 海选赛段场次补充轮次评分:每轮对应一名选手,含累计总分与各裁判打分明细。
     * 非海选赛段不处理(避免多余查询)。
     */
    private void fillAuditionRoundScores(List<TMatchVo> matches,
                                         Map<Long, List<TMatchParticipant>> byMatch,
                                         Map<Long, String> nameById,
                                         Map<Long, String> numberById) {
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList();
        if (stageIds.isEmpty()) {
            return;
        }
        Map<Long, String> stageModeById = stageMapper.selectByIds(stageIds).stream()
            .collect(Collectors.toMap(TStage::getId, TStage::getStageMode, (a, b) -> a));

        List<TMatchVo> auditionMatches = matches.stream()
            .filter(m -> stageCap(stageModeById.get(m.getStageId())).audition())
            .toList();
        if (auditionMatches.isEmpty()) {
            return;
        }
        List<Long> matchIds = auditionMatches.stream().map(TMatchVo::getId).filter(Objects::nonNull).toList();

        // 本场当前回合:逐选手制一场一回合,全员共享;「谁在这场」由 participant(entry)承载
        List<TMatchRound> rounds = matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .orderByAsc(TMatchRound::getMatchId)
                .orderByAsc(TMatchRound::getRoundSequence));
        Map<Long, TMatchRound> roundByMatch = new HashMap<>();
        for (TMatchRound r : rounds) {
            roundByMatch.putIfAbsent(r.getMatchId(), r);
        }

        List<Long> roundIds = rounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        List<TRoundScore> allScores = roundIds.isEmpty() ? List.of()
            : roundScoreMapper.selectList(
                    Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
                .stream()
                .filter(s -> s.getCompetitorId() != null)
                .toList();
        // 打分明细按轮次归组,再按 competitor_id 区分到人
        Map<Long, List<TRoundScore>> scoresByRound = allScores.stream()
            .collect(Collectors.groupingBy(TRoundScore::getRoundId));

        List<Long> refereeIds = allScores.stream()
            .map(TRoundScore::getRefereeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> refereeNameById = refereeIds.isEmpty() ? Map.of()
            : refereeMapper.selectByIds(refereeIds).stream()
                .collect(Collectors.toMap(TReferee::getId, TReferee::getName, (a, b) -> a));

        for (TMatchVo vo : auditionMatches) {
            // 上场名单 = 本场参赛行(按入场顺序),不再按「一人一轮」推导
            List<TMatchParticipant> partList = (byMatch.get(vo.getId()) == null
                ? List.<TMatchParticipant>of() : byMatch.get(vo.getId())).stream()
                .filter(p -> p.getCompetitorId() != null)
                .sorted(Comparator.comparingLong((TMatchParticipant p) ->
                    p.getDisplaySlotIndex() == null ? Long.MAX_VALUE : p.getDisplaySlotIndex())
                    .thenComparingLong(p -> p.getId() == null ? Long.MAX_VALUE : p.getId()))
                .toList();
            if (partList.isEmpty()) {
                continue;
            }
            TMatchRound round = roundByMatch.get(vo.getId());
            List<MatchRoundScoreVo> roundScores = new ArrayList<>();
            for (TMatchParticipant p : partList) {
                Long cid = p.getCompetitorId();
                List<TRoundScore> rs = round == null ? List.of()
                    : scoresByRound.getOrDefault(round.getId(), List.of()).stream()
                        .filter(s -> Objects.equals(s.getCompetitorId(), cid))
                        .toList();
                MatchRoundScoreVo item = buildAuditionRoundItem(round, cid, rs,
                    nameById, numberById, p, refereeNameById);
                // 展示序号取入场顺序(上场第几位),round 序号不再等于人
                item.setRoundSequence(p.getDisplaySlotIndex());
                roundScores.add(item);
            }
            vo.setRoundScores(roundScores);
        }
    }

    /**
     * 构建海选单轮评分项:总分优先取参与方累计分(与海选结算口径一致),无累计时用裁判分求和兜底;
     * 结算后带出结果状态(晋级/淘汰),供导播台在确认晋级前展示二海晋级者。
     */
    private MatchRoundScoreVo buildAuditionRoundItem(TMatchRound r, Long competitorId,
                                                     List<TRoundScore> scores,
                                                     Map<Long, String> nameById,
                                                     Map<Long, String> numberById,
                                                     TMatchParticipant p,
                                                     Map<Long, String> refereeNameById) {
        MatchRoundScoreVo item = new MatchRoundScoreVo();
        item.setRoundId(r == null ? null : r.getId());
        item.setRoundSequence(r == null ? null : r.getRoundSequence());
        item.setCompetitorId(competitorId);
        item.setCompetitorName(competitorId == null ? null : nameById.get(competitorId));
        item.setCompetitorNumber(competitorId == null ? null : numberById.get(competitorId));

        List<MatchRoundScoreVo.RefereeScore> refScores = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        if (scores != null) {
            for (TRoundScore s : scores) {
                if (s.getScore() == null) {
                    continue;
                }
                sum = sum.add(s.getScore());
                MatchRoundScoreVo.RefereeScore ref = new MatchRoundScoreVo.RefereeScore();
                ref.setRefereeId(s.getRefereeId());
                ref.setRefereeName(s.getRefereeId() == null ? null : refereeNameById.get(s.getRefereeId()));
                ref.setScore(s.getScore());
                refScores.add(ref);
            }
        }
        BigDecimal total = p != null && p.getScoreValue() != null ? p.getScoreValue()
            : refScores.isEmpty() ? null : sum;
        item.setScore(total);
        item.setRefereeScores(refScores.isEmpty() ? null : refScores);
        if (p != null && p.getOutcomeStatus() != null) {
            item.setOutcomeStatus(p.getOutcomeStatus());
        }
        return item;
    }

    /**
     * 填充公布模式与实时判罚投票(裁判端投票进度,供导播台实时查看)。
     */
    private void fillPublishInfo(List<TMatchVo> matches, Map<Long, List<TMatchParticipant>> byMatch) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> publishModeByStage = new HashMap<>();
        if (!stageIds.isEmpty()) {
            for (TStage s : stageMapper.selectByIds(stageIds)) {
                publishModeByStage.put(s.getId(), readPublishMode(s));
            }
        }
        // 裁判分配一次批量取回:此前每个需要显示投票进度的场次都回查一次赛段裁判
        Map<Long, List<Long>> refereesByStage = stageIds.isEmpty() ? Map.of()
            : refereeStageService.getRefereeIdsByStageIds(stageIds);
        // 裁判姓名也一次批量取回(覆盖所有赛段),下面按场次填判罚明细时不再逐场 selectByIds
        Set<Long> allAssignedRefereeIds = refereesByStage.values().stream()
            .flatMap(List::stream).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> assignedRefereeNameById = allAssignedRefereeIds.isEmpty() ? Map.of()
            : refereeMapper.selectByIds(allAssignedRefereeIds).stream()
                .collect(Collectors.toMap(TReferee::getId, TReferee::getName, (a, b) -> a));
        // 需要统计投票的场次:STANDARD、进行中、公布模式非 DIRECTOR
        List<TMatchVo> votingMatches = matches.stream()
            .filter(m -> "STANDARD".equals(m.getMatchMode())
                && StageConstants.MATCH_GAMING.equals(m.getStatus())
                && !"DIRECTOR".equalsIgnoreCase(publishModeByStage.get(m.getStageId())))
            .toList();
        Set<Long> votingMatchIds = votingMatches.stream().map(TMatchVo::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TMatchRound> latestRoundByMatch = new HashMap<>();
        Map<Long, List<TRoundScore>> votesByRound = Map.of();
        if (!votingMatches.isEmpty()) {
            List<TMatchRound> rounds = matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, votingMatchIds)
                    .orderByAsc(TMatchRound::getMatchId)
                    .orderByAsc(TMatchRound::getRoundSequence));
            Map<Long, List<TMatchRound>> roundsByMatch = rounds.stream()
                .collect(Collectors.groupingBy(TMatchRound::getMatchId));
            for (var e : roundsByMatch.entrySet()) {
                latestRoundByMatch.put(e.getKey(), e.getValue().get(e.getValue().size() - 1));
            }
            List<Long> roundIds = latestRoundByMatch.values().stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
            if (!roundIds.isEmpty()) {
                votesByRound = roundScoreMapper.selectList(
                        Wrappers.<TRoundScore>lambdaQuery()
                            .in(TRoundScore::getRoundId, roundIds)
                            .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE))
                    .stream().collect(Collectors.groupingBy(TRoundScore::getRoundId));
            }
        }

        for (TMatchVo vo : matches) {
            vo.setPublishMode(publishModeByStage.get(vo.getStageId()));
            vo.setPendingPublish(StageConstants.MATCH_GAMING.equals(vo.getStatus())
                && StringUtils.isNotBlank(vo.getResultJson()));
            if (!votingMatchIds.contains(vo.getId())) {
                continue;
            }
            List<Long> assignedIds = refereesByStage.getOrDefault(vo.getStageId(), List.of());
            vo.setTotalReferees(assignedIds.size());
            if (assignedIds.isEmpty()) {
                continue;
            }
            TMatchRound round = latestRoundByMatch.get(vo.getId());
            if (round == null) {
                continue;
            }
            List<TRoundScore> votes = votesByRound.getOrDefault(round.getId(), List.of());
            long voted = votes.stream().map(TRoundScore::getRefereeId).filter(Objects::nonNull).distinct().count();
            vo.setVotedReferees((int) voted);
            List<TMatchParticipant> parts = byMatch.get(vo.getId());
            Long left = parts != null && parts.size() > 0 ? parts.get(0).getCompetitorId() : null;
            Long right = parts != null && parts.size() > 1 ? parts.get(1).getCompetitorId() : null;
            int leftVotes = 0, rightVotes = 0;
            Set<Long> drawReferees = new HashSet<>();
            for (TRoundScore v : votes) {
                if (v.getScore() == null) {
                    continue;
                }
                if (v.getScore().compareTo(java.math.BigDecimal.ONE) == 0) {
                    if (Objects.equals(v.getCompetitorId(), left)) {
                        leftVotes++;
                    } else if (Objects.equals(v.getCompetitorId(), right)) {
                        rightVotes++;
                    }
                } else if (v.getScore().compareTo(new java.math.BigDecimal("0.5")) == 0 && v.getRefereeId() != null) {
                    drawReferees.add(v.getRefereeId());
                }
            }
            vo.setLeftVotes(leftVotes);
            vo.setRightVotes(rightVotes);
            vo.setDrawVotes(drawReferees.size());

            // 各裁判判罚明细(一行一个裁判:红/蓝/平/未判)
            Map<Long, String> refNameById = assignedRefereeNameById;
            List<TMatchVo.RefereeVoteInfo> rvs = new ArrayList<>();
            for (Long rid : assignedIds) {
                TMatchVo.RefereeVoteInfo rv = new TMatchVo.RefereeVoteInfo();
                rv.setRefereeId(rid);
                rv.setRefereeName(refNameById.get(rid));
                String vote = null;
                for (TRoundScore v : votes) {
                    if (v.getRefereeId() == null || !v.getRefereeId().equals(rid) || v.getScore() == null) {
                        continue;
                    }
                    if (v.getScore().compareTo(java.math.BigDecimal.ONE) == 0) {
                        if (Objects.equals(v.getCompetitorId(), left)) {
                            vote = "LEFT";
                            break;
                        } else if (Objects.equals(v.getCompetitorId(), right)) {
                            vote = "RIGHT";
                            break;
                        }
                    } else if (v.getScore().compareTo(new java.math.BigDecimal("0.5")) == 0) {
                        vote = "DRAW";
                        break;
                    }
                }
                rv.setVote(vote);
                rvs.add(rv);
            }
            vo.setRefereeVotes(rvs);
        }
    }

    private String readPublishMode(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        // 淘汰赛读 knockout.publishMode,擂台赛等读顶层 publishMode(统一在 RuleConfigHolder)
        return rc == null ? "AUTO" : rc.resolvePublishMode();
    }

    /**
     * 海选:给场次填「本场(本圈/加赛)晋级人数 / 本赛段共晋级人数 / 加赛轮次名」。
     *
     * <p>口径与结算一致(见 {@code AuditionAdvanceInfoSupport}):正式圈 = 本圈名额,
     * 加赛 = 本圈剩余名额。导播台要在圈卡片上直接告诉 MC "这一场晋级几个人"。</p>
     */
    private void fillAuditionAdvanceInfo(List<TMatchVo> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId)
            .filter(Objects::nonNull).distinct().toList();
        if (stageIds.isEmpty()) {
            return;
        }
        List<TStage> stages = stageMapper.selectByIds(stageIds);
        // 各海选赛段的全部场次一次取回后分组,替代逐赛段 selectList
        List<Long> auditionStageIds = stages.stream()
            .filter(s -> stageCap(s.getStageMode()).audition())
            .map(TStage::getId).toList();
        Map<Long, List<TMatch>> stageMatchesByStage = auditionStageIds.isEmpty() ? Map.of()
            : baseMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .in(TMatch::getStageId, auditionStageIds))
                .stream()
                .filter(m -> m.getStageId() != null)
                .collect(Collectors.groupingBy(TMatch::getStageId));
        for (TStage stage : stages) {
            if (!stageCap(stage.getStageMode()).audition()) {
                continue;
            }
            List<TMatch> stageMatches = stageMatchesByStage.getOrDefault(stage.getId(), List.of());
            Map<Long, Integer> advanceByMatch = auditionAdvanceInfoSupport.matchAdvanceCounts(stage, stageMatches);
            int stageTotal = auditionAdvanceInfoSupport.stageAdvanceCount(stage);
            Map<Long, TMatch> matchById = stageMatches.stream()
                .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
            for (TMatchVo vo : matches) {
                if (!stage.getId().equals(vo.getStageId())) {
                    continue;
                }
                vo.setStageAdvanceCount(stageTotal);
                vo.setAdvanceCount(advanceByMatch.getOrDefault(vo.getId(), 0));
                TMatch m = vo.getId() == null ? null : matchById.get(vo.getId());
                vo.setTiebreakerRound(m == null ? null : auditionAdvanceInfoSupport.tiebreakerRoundName(m));
            }
        }
    }

    /**
     * 填充淘汰赛场次各轮判罚明细:每轮两名参赛者 + 每名裁判 LEFT/RIGHT/DRAW(未判为 null)。
     * 与 fillPublishInfo 的票型口径一致(1.0=判左/右胜,0.5=判平)。
     */
    private void fillKnockoutRoundVotes(List<TMatchVo> matches,
                                        Map<Long, List<TMatchParticipant>> byMatch,
                                        Map<Long, String> nameById) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList();
        if (stageIds.isEmpty()) {
            return;
        }
        Map<Long, String> stageModeById = stageMapper.selectByIds(stageIds).stream()
            .collect(Collectors.toMap(TStage::getId, TStage::getStageMode, (a, b) -> a));

        List<TMatchVo> koMatches = matches.stream()
            .filter(m -> stageCap(stageModeById.get(m.getStageId())).knockout()
                && "STANDARD".equals(m.getMatchMode()))
            .toList();
        if (koMatches.isEmpty()) {
            return;
        }
        List<Long> matchIds = koMatches.stream().map(TMatchVo::getId).filter(Objects::nonNull).toList();
        if (matchIds.isEmpty()) {
            return;
        }
        List<TMatchRound> rounds = matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .orderByAsc(TMatchRound::getMatchId)
                .orderByAsc(TMatchRound::getRoundSequence));
        if (rounds.isEmpty()) {
            return;
        }
        Map<Long, List<TMatchRound>> roundsByMatch = rounds.stream()
            .collect(Collectors.groupingBy(TMatchRound::getMatchId));
        List<Long> roundIds = rounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TRoundScore>> votesByRound = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(
                    Wrappers.<TRoundScore>lambdaQuery()
                        .in(TRoundScore::getRoundId, roundIds)
                        .eq(TRoundScore::getAction, StageConstants.SCORE_ACTION_VOTE))
                .stream().collect(Collectors.groupingBy(TRoundScore::getRoundId));

        // 每赛段裁判名单 + 姓名
        Map<Long, List<Long>> refereeIdsByStage = new HashMap<>();
        for (TMatchVo vo : koMatches) {
            refereeIdsByStage.computeIfAbsent(vo.getStageId(),
                sid -> refereeStageService.getRefereeIdsByStageId(sid));
        }
        Set<Long> allRefereeIds = refereeIdsByStage.values().stream()
            .flatMap(List::stream).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> refNameById = allRefereeIds.isEmpty() ? Map.of()
            : refereeMapper.selectByIds(allRefereeIds).stream()
                .collect(Collectors.toMap(TReferee::getId, TReferee::getName, (a, b) -> a));

        for (TMatchVo vo : koMatches) {
            List<TMatchRound> roundList = roundsByMatch.get(vo.getId());
            if (roundList == null || roundList.isEmpty()) {
                continue;
            }
            List<TMatchParticipant> parts = byMatch.get(vo.getId());
            Long left = parts != null && !parts.isEmpty() ? parts.get(0).getCompetitorId() : null;
            Long right = parts != null && parts.size() > 1 ? parts.get(1).getCompetitorId() : null;
            List<Long> assigned = refereeIdsByStage.getOrDefault(vo.getStageId(), List.of());

            List<TMatchVo.RoundVoteInfo> roundVotes = new ArrayList<>();
            for (int i = 0; i < roundList.size(); i++) {
                TMatchRound r = roundList.get(i);
                List<TRoundScore> votes = votesByRound.getOrDefault(r.getId(), List.of());
                TMatchVo.RoundVoteInfo rv = new TMatchVo.RoundVoteInfo();
                rv.setRoundId(r.getId());
                rv.setRoundSequence(r.getRoundSequence());
                rv.setStatus(r.getStatus());
                // BO1 平局加赛轮:已结算且非最后一轮即平局局。
                // BO3/BO5 有胜局也会产生后续局,不能据此判平(局分见 seriesLeftWins/seriesRightWins)。
                boolean boSeries = vo.getBestOf() != null && vo.getBestOf() > 1;
                if (!boSeries && StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < roundList.size() - 1) {
                    rv.setOutcome("DRAW");
                }
                // 本轮实际参赛者:按设计取 round_score 中出现的 competitor(去重),
                // 尚无判罚记录时保底用场次左右参赛位
                List<Long> roundCompetitorIds = votes.stream()
                    .map(TRoundScore::getCompetitorId).filter(Objects::nonNull).distinct().toList();
                if (roundCompetitorIds.isEmpty()) {
                    List<Long> fallback = new ArrayList<>();
                    if (left != null) {
                        fallback.add(left);
                    }
                    if (right != null) {
                        fallback.add(right);
                    }
                    roundCompetitorIds = fallback;
                }
                // 按场次左右位排序(左→右),其余按出现顺序
                List<Long> ordered = new ArrayList<>(roundCompetitorIds);
                ordered.sort((a, b) -> {
                    int ra = Objects.equals(a, left) ? 0 : (Objects.equals(a, right) ? 1 : 2);
                    int rb = Objects.equals(b, left) ? 0 : (Objects.equals(b, right) ? 1 : 2);
                    return Integer.compare(ra, rb);
                });
                Long rLeft = ordered.isEmpty() ? null : ordered.get(0);
                Long rRight = ordered.size() > 1 ? ordered.get(1) : null;
                rv.setLeftName(rLeft == null ? null : nameById.get(rLeft));
                rv.setRightName(rRight == null ? null : nameById.get(rRight));
                // 单裁判赛事/历史数据:判罚可能未落库,已结算场次以场次胜者回显该裁判判罚,
                // 保证导播台展开后仍能看到红/蓝判定(DIRECTOR 模式由导播台判定,不回显)
                String fallbackVote = null;
                if (votes.isEmpty()
                    && StageConstants.MATCH_SETTLED.equals(vo.getStatus())
                    && assigned.size() == 1
                    && !"DIRECTOR".equalsIgnoreCase(vo.getPublishMode())) {
                    if (Boolean.TRUE.equals(vo.getLeftWin())) {
                        fallbackVote = "LEFT";
                    } else if (Boolean.TRUE.equals(vo.getRightWin())) {
                        fallbackVote = "RIGHT";
                    }
                }
                List<TMatchVo.RefereeVoteInfo> refVotes = new ArrayList<>();
                for (Long rid : assigned) {
                    TMatchVo.RefereeVoteInfo ref = new TMatchVo.RefereeVoteInfo();
                    ref.setRefereeId(rid);
                    ref.setRefereeName(refNameById.get(rid));
                    String v = interpretVote(votes, rid, rLeft, rRight);
                    ref.setVote(v != null ? v : fallbackVote);
                    refVotes.add(ref);
                }
                rv.setRefereeVotes(refVotes);
                // 本轮胜方:与结算口径一致(左胜票>右胜票=LEFT,反之 RIGHT,否则 DRAW);
                // 全部裁判判完才定论;无判罚记录的已结算场次以场次胜者为该轮胜方
                int leftWins = 0, rightWins = 0;
                for (TRoundScore v : votes) {
                    if (v.getScore() == null) {
                        continue;
                    }
                    if (v.getScore().compareTo(java.math.BigDecimal.ONE) == 0) {
                        if (Objects.equals(v.getCompetitorId(), rLeft)) {
                            leftWins++;
                        } else if (Objects.equals(v.getCompetitorId(), rRight)) {
                            rightWins++;
                        }
                    }
                }
                long votedRefs = votes.stream().map(TRoundScore::getRefereeId)
                    .filter(Objects::nonNull).distinct().count();
                // 导播台直接判定(不经过裁判)会把本局判定写成 referee_id=0 的明细:
                // 这种局不能按"应到裁判是否投满"判断,只要有该明细就能定本局胜方。
                boolean directorJudged = votes.stream()
                    .anyMatch(v -> v.getRefereeId() != null && v.getRefereeId() == 0L);
                String winnerSide = null;
                if (directorJudged || (!assigned.isEmpty() && votedRefs >= assigned.size())) {
                    winnerSide = leftWins > rightWins ? "LEFT"
                        : rightWins > leftWins ? "RIGHT" : "DRAW";
                }
                // 兜底仅限 BO1:BO3/BO5 多局时不能用整场胜者给每一局贴上同一个结果
                if (winnerSide == null && StageConstants.MATCH_SETTLED.equals(vo.getStatus()) && !boSeries) {
                    if (Boolean.TRUE.equals(vo.getLeftWin())) {
                        winnerSide = "LEFT";
                    } else if (Boolean.TRUE.equals(vo.getRightWin())) {
                        winnerSide = "RIGHT";
                    }
                }
                rv.setWinnerSide(winnerSide);
                roundVotes.add(rv);
            }
            vo.setRoundVotes(roundVotes);
        }
    }

    /**
     * 单名裁判在某轮的判罚:1.0 且指向左/右参赛者 → LEFT/RIGHT;0.5 → DRAW;否则未判。
     */
    private String interpretVote(List<TRoundScore> votes, Long refereeId, Long left, Long right) {
        for (TRoundScore v : votes) {
            if (v.getRefereeId() == null || !v.getRefereeId().equals(refereeId) || v.getScore() == null) {
                continue;
            }
            if (v.getScore().compareTo(BigDecimal.ONE) == 0) {
                if (Objects.equals(v.getCompetitorId(), left)) {
                    return "LEFT";
                }
                if (Objects.equals(v.getCompetitorId(), right)) {
                    return "RIGHT";
                }
            } else if (v.getScore().compareTo(new BigDecimal("0.5")) == 0) {
                return "DRAW";
            }
        }
        return null;
    }

    private LambdaQueryWrapper<TMatch> buildQueryWrapper(TMatchBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TMatch> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TMatch::getId);
        lqw.eq(bo.getTournamentId() != null, TMatch::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getStageId() != null, TMatch::getStageId, bo.getStageId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TMatch::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getDisplayZone()), TMatch::getDisplayZone, bo.getDisplayZone());
        lqw.eq(bo.getDisplayRow() != null, TMatch::getDisplayRow, bo.getDisplayRow());
        lqw.eq(bo.getDisplayCol() != null, TMatch::getDisplayCol, bo.getDisplayCol());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), TMatch::getStatus, bo.getStatus());
        lqw.eq(StringUtils.isNotBlank(bo.getMatchMode()), TMatch::getMatchMode, bo.getMatchMode());
        lqw.eq(StringUtils.isNotBlank(bo.getPromotionRule()), TMatch::getPromotionRule, bo.getPromotionRule());
        return lqw;
    }

    /**
     * 新增比赛场次
     *
     * @param bo 比赛场次
     * @return 新增后的比赛场次
     */
    @Override
    public TMatchVo insertByBo(TMatchBo bo) {
        TMatch add = MapstructUtils.convert(bo, TMatch.class);
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TMatchVo.class);
    }

    /**
     * 修改比赛场次
     *
     * @param bo 比赛场次
     * @return 修改后的比赛场次
     */
    @Override
    public TMatchVo updateByBo(TMatchBo bo) {
        TMatch update = MapstructUtils.convert(bo, TMatch.class);
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TMatchVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TMatch entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除比赛场次信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    /** 赛制画像(读模型里替代 mode 字符串/枚举比较)。 */
    private StageModeProfile stageCap(String stageMode) {
        return StageModeProfiles.of(stageMode);
    }
}
