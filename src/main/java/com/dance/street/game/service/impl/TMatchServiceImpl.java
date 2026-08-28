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
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
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
        Map<Long, String> nameById = competitorIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(competitorIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, TCompetitor::getName, (a, b) -> a));

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
                boolean rightWin = isWinner(right);
                vo.setRightWin(rightWin);
                if (rightWin) {
                    winnerName = vo.getRightName();
                }
            }
            vo.setWinnerName(winnerName);
        }

        // 海选赛段:补充每轮(每名选手)的评分明细
        fillAuditionRoundScores(matches, byMatch, nameById);

        // 公布模式 + 实时判罚投票
        fillPublishInfo(matches, byMatch);
        // 淘汰赛:本场各轮判罚明细(每轮参赛者取自 round_score,保底用场次左右位)
        fillKnockoutRoundVotes(matches, byMatch, nameById);
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
                                         Map<Long, String> nameById) {
        List<Long> stageIds = matches.stream().map(TMatchVo::getStageId).filter(Objects::nonNull).distinct().toList();
        if (stageIds.isEmpty()) {
            return;
        }
        Map<Long, String> stageModeById = stageMapper.selectByIds(stageIds).stream()
            .collect(Collectors.toMap(TStage::getId, TStage::getStageMode, (a, b) -> a));

        List<TMatchVo> auditionMatches = matches.stream()
            .filter(m -> "AUDITION".equals(stageModeById.get(m.getStageId())))
            .toList();
        if (auditionMatches.isEmpty()) {
            return;
        }
        List<Long> matchIds = auditionMatches.stream().map(TMatchVo::getId).filter(Objects::nonNull).toList();

        List<TMatchRound> rounds = matchRoundMapper.selectList(
            Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .orderByAsc(TMatchRound::getMatchId)
                .orderByAsc(TMatchRound::getRoundSequence));
        Map<Long, List<TMatchRound>> roundsByMatch = rounds.stream()
            .collect(Collectors.groupingBy(TMatchRound::getMatchId));
        if (rounds.isEmpty()) {
            return;
        }

        List<Long> roundIds = rounds.stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        List<TRoundScore> allScores = roundScoreMapper.selectList(
            Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
            .stream()
            .filter(s -> s.getCompetitorId() != null)
            .toList();
        // 打分明细按轮次归组:常规海选每轮一名选手;二海(加赛)是单轮多选手共享轮次
        Map<Long, List<TRoundScore>> scoresByRound = allScores.stream()
            .collect(Collectors.groupingBy(TRoundScore::getRoundId));
        // 兼容历史数据:早期写入把所有选手分挂在同一轮,按选手回退归组兜底
        Map<Long, List<TRoundScore>> scoresByCompetitor = allScores.stream()
            .collect(Collectors.groupingBy(TRoundScore::getCompetitorId));

        List<Long> refereeIds = allScores.stream()
            .map(TRoundScore::getRefereeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> refereeNameById = refereeIds.isEmpty() ? Map.of()
            : refereeMapper.selectByIds(refereeIds).stream()
                .collect(Collectors.toMap(TReferee::getId, TReferee::getName, (a, b) -> a));

        for (TMatchVo vo : auditionMatches) {
            List<TMatchRound> roundList = roundsByMatch.get(vo.getId());
            if (roundList == null || roundList.isEmpty()) {
                continue;
            }
            Map<Long, TMatchParticipant> participantByCid = (byMatch.get(vo.getId()) == null ? List.<TMatchParticipant>of()
                : byMatch.get(vo.getId())).stream()
                .filter(p -> p.getCompetitorId() != null)
                .collect(Collectors.toMap(TMatchParticipant::getCompetitorId, p -> p, (a, b) -> a));

            List<MatchRoundScoreVo> roundScores = new ArrayList<>();
            for (TMatchRound r : roundList) {
                List<TRoundScore> roundScoresOfRound = scoresByRound.getOrDefault(r.getId(), List.of());
                Set<Long> matchRoundIds = roundList.stream()
                    .map(TMatchRound::getId).filter(Objects::nonNull).collect(Collectors.toSet());
                // 二海(加赛):轮次未绑定选手,单轮共享多名选手评分,按参赛方逐人展示(与海选主赛一致)
                if (r.getCompetitorId() == null) {
                    for (TMatchParticipant p : byMatch.getOrDefault(vo.getId(), List.of())) {
                        if (p.getCompetitorId() == null) {
                            continue;
                        }
                        roundScores.add(buildAuditionRoundItem(r, p.getCompetitorId(),
                            roundScoresOfRound.stream()
                                .filter(s -> Objects.equals(s.getCompetitorId(), p.getCompetitorId()))
                                .toList(),
                            nameById, participantByCid, refereeNameById));
                    }
                    continue;
                }
                // 常规海选:取本轮该选手评分;本轮无分时回退到同场其他轮次(兼容历史单轮写入)
                List<TRoundScore> rs = roundScoresOfRound.stream()
                    .filter(s -> Objects.equals(s.getCompetitorId(), r.getCompetitorId()))
                    .toList();
                if (rs.isEmpty()) {
                    rs = scoresByCompetitor.getOrDefault(r.getCompetitorId(), List.of()).stream()
                        .filter(s -> matchRoundIds.contains(s.getRoundId()))
                        .toList();
                }
                roundScores.add(buildAuditionRoundItem(r, r.getCompetitorId(), rs,
                    nameById, participantByCid, refereeNameById));
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
                                                     Map<Long, TMatchParticipant> participantByCid,
                                                     Map<Long, String> refereeNameById) {
        MatchRoundScoreVo item = new MatchRoundScoreVo();
        item.setRoundId(r.getId());
        item.setRoundSequence(r.getRoundSequence());
        item.setCompetitorId(competitorId);
        item.setCompetitorName(competitorId == null ? null : nameById.get(competitorId));

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
        TMatchParticipant p = competitorId == null ? null : participantByCid.get(competitorId);
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
            List<Long> assignedIds = refereeStageService.getRefereeIdsByStageId(vo.getStageId());
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
            Map<Long, String> refNameById = assignedIds.isEmpty() ? Map.of()
                : refereeMapper.selectByIds(assignedIds).stream()
                    .collect(Collectors.toMap(TReferee::getId, TReferee::getName, (a, b) -> a));
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
        if (rc != null && rc.getKnockout() != null && StringUtils.isNotBlank(rc.getKnockout().getPublishMode())) {
            return rc.getKnockout().getPublishMode();
        }
        return "AUTO";
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
            .filter(m -> "KNOCKOUT".equals(stageModeById.get(m.getStageId()))
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
                // 已结算且非最后一轮的轮次 = 平局加赛轮(只有平局才会产生后续轮次)
                if (StageConstants.MATCH_SETTLED.equals(r.getStatus()) && i < roundList.size() - 1) {
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
                String winnerSide = null;
                if (!assigned.isEmpty() && votedRefs >= assigned.size()) {
                    winnerSide = leftWins > rightWins ? "LEFT"
                        : rightWins > leftWins ? "RIGHT" : "DRAW";
                }
                if (winnerSide == null && StageConstants.MATCH_SETTLED.equals(vo.getStatus())) {
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
}
