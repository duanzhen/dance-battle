package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.settle.ArenaQueueSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 擂台赛的运行与总览:逐场开赛、弃权补位、队列/积分总览。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者。擂台赛没有固定签表,
 * 对手由"轮转队列"逐场产生,所以这块自成一条业务线;队列与积分的算法在
 * {@link ArenaQueueSupport},这里只负责把它变成场次、参赛方行与总览视图。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ArenaRunService {

    private final StageLookup stageLookup;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TPlayerMapper playerMapper;
    private final TRoundScoreMapper roundScoreMapper;
    /** 赛段级结果(晋级/淘汰/名次)的唯一写入口 */
    private final CompetitorOutcomeWriter outcomeWriter;
    /** 擂台轮转队列/积分口径(查询与结算共用) */
    private final ArenaQueueSupport arenaQueueSupport;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;

    /** 开始下一场对决:从轮转队列取队首两人开一场(有进行中的对决时拒绝)。 */
    @Transactional(rollbackFor = Exception.class)
    public void startNextArenaMatch(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持逐场开赛");
        }
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段尚未开始,无法开始下一场对决");
        }
        long gaming = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
        if (gaming > 0) {
            throw new ServiceException("已有进行中的对决,请先完成或重启当前对决");
        }
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        if (queue.size() < 2) {
            throw new ServiceException("擂台赛至少需要 2 名参赛者,当前 {} 名", queue.size());
        }

        long count = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        TMatch m = new TMatch();
        m.setTournamentId(stage.getTournamentId());
        m.setTenantId(stage.getTenantId());
        m.setStageId(stage.getId());
        m.setName("擂台-" + (count + 1));
        m.setDisplayRow(count + 1);
        m.setDisplayCol(1L);
        m.setStatus(StageConstants.MATCH_GAMING);
        m.setMatchMode(MatchModeEnum.STANDARD.getCode());
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);

        TMatchRound round = new TMatchRound();
        round.setTournamentId(stage.getTournamentId());
        round.setTenantId(stage.getTenantId());
        round.setMatchId(m.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(round);

        insertArenaParticipant(m, queue.get(0), 1L);
        insertArenaParticipant(m, queue.get(1), 2L);

        log.info("擂台赛赛段[{}]创建第{}场对决:{} vs {}", stageId, count + 1, queue.get(0), queue.get(1));
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "match");
    }

    /** 永久弃权:不再参与排队;正在对决时同场次由队列下一位补位。 */
    @Transactional(rollbackFor = Exception.class)
    public void withdrawArenaCompetitor(Long stageId, Long competitorId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持参赛选手弃权");
        }
        TCompetitor comp = competitorMapper.selectById(competitorId);
        if (comp == null || !Objects.equals(comp.getStageId(), stageId)) {
            throw new ServiceException("参赛选手不存在或不属于当前赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(comp.getOutcomeStatus())) {
            return; // 已弃权,幂等
        }
        outcomeWriter.writeOutcome(competitorId, OutcomeStatusEnum.WITHDRAWN.getCode());
        // 补位:同一场次内把弃权选手替换为队列下一位(不开新场)
        replaceArenaMatchParticipant(stageId, competitorId);
        log.info("擂台赛[{}]参赛选手[{}]弃权,不再参与排队", stageId, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    /** 临时弃权:跳过本轮(排到队尾一次),之后按正常轮转继续参与。 */
    @Transactional(rollbackFor = Exception.class)
    public void tempWithdrawArenaCompetitor(Long stageId, Long competitorId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持临时弃权");
        }
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段未在进行中,无法临时弃权");
        }
        TCompetitor comp = competitorMapper.selectById(competitorId);
        if (comp == null || !Objects.equals(comp.getStageId(), stageId)) {
            throw new ServiceException("参赛选手不存在或不属于当前赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(comp.getOutcomeStatus())) {
            throw new ServiceException("该选手已永久弃权,无法临时弃权");
        }
        // 临时弃权 = 跳过本轮:记录跳过标记(排到队尾一次),之后按正常轮转继续参与排队/对阵/排名。
        // 标记里带上"当时的场次数",队列回放时才能把这次挪位放回它在时间线上的位置——
        // 否则每次重算都把他追加到末尾,人就再也上不了场了。
        long matchCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        TCompetitor upd = new TCompetitor();
        upd.setId(competitorId);
        upd.setRemark(arenaQueueSupport.appendArenaSkipMark(comp.getRemark(), matchCount));
        competitorMapper.updateById(upd);
        // 补位:同一场次内把临时弃权选手替换为队列下一位(不开新场)
        replaceArenaMatchParticipant(stageId, competitorId);
        log.info("擂台赛[{}]选手[{}]临时弃权,排到队尾,同场次下一位补位", stageId, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
    }

    /**
     * 擂台总览:轮转队列(含积分/头像/排位)+ 当前对决 + 已打场次。
     */
    public ArenaOverviewVo getArenaOverview(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.ARENA.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅擂台赛赛段支持擂台总览");
        }
        ArenaOverviewVo vo = new ArenaOverviewVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStatus(stage.getStatus());

        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        Map<Long, TCompetitor> compMap = comps.stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c));
        Map<Long, Integer> points = arenaQueueSupport.arenaPoints(stageId);
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        Map<Long, String> avatars = loadAvatarMap(new ArrayList<>(compMap.keySet()));

        List<ArenaOverviewVo.CompetitorInfo> queueList = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            queueList.add(toArenaCompetitor(queue.get(i), compMap, avatars, points, i + 1));
        }
        vo.setQueue(queueList);

        List<TMatch> gaming = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
            .orderByAsc(TMatch::getId));
        if (!gaming.isEmpty()) {
            TMatch cur = gaming.get(0);
            List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, cur.getId())
                .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
            ArenaOverviewVo.MatchInfo mi = new ArenaOverviewVo.MatchInfo();
            mi.setMatchId(cur.getId());
            mi.setMatchName(cur.getName());
            if (!parts.isEmpty() && parts.get(0).getCompetitorId() != null) {
                mi.setDefender(toArenaCompetitor(parts.get(0).getCompetitorId(), compMap, avatars, points, 1));
            }
            if (parts.size() > 1 && parts.get(1).getCompetitorId() != null) {
                mi.setChallenger(toArenaCompetitor(parts.get(1).getCompetitorId(), compMap, avatars, points, 2));
            }
            vo.setCurrentMatch(mi);
        }

        long settled = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        vo.setBattleCount(settled);
        return vo;
    }

    /**
     * 弃权补位:不新建场次,把进行中对决里弃权选手的参赛方替换为队列下一位,
     * 并清空本场已提交结果(替换者从零开始)。
     * 擂主(slot1)弃权时:对手自动变擂主,队列下一位顶上来挑战;
     * 挑战者(slot2)弃权时:擂主不动,队列下一位顶上来挑战。
     * 无替补时移除对应参赛方行。
     */
    private void replaceArenaMatchParticipant(Long stageId, Long withdrawnId) {
        List<Long> gamingMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        // 进行中场次的参赛方一次批量取回后按场次分组(通常只有 1 场,避免逐场回查)
        Map<Long, List<TMatchParticipant>> partsByMatch = gamingMatchIds.isEmpty() ? Map.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, gamingMatchIds)
                    .orderByAsc(TMatchParticipant::getDisplaySlotIndex))
                .stream()
                .filter(p -> p.getMatchId() != null)
                .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        Long matchId = null;
        Long withdrawnSlot = null;
        Long otherId = null;
        for (Long mid : gamingMatchIds) {
            List<TMatchParticipant> ps = partsByMatch.getOrDefault(mid, List.of());
            for (TMatchParticipant p : ps) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                if (p.getCompetitorId().equals(withdrawnId)) {
                    matchId = mid;
                    withdrawnSlot = p.getDisplaySlotIndex();
                } else {
                    otherId = p.getCompetitorId();
                }
            }
            if (matchId != null) {
                break;
            }
        }
        if (matchId == null || withdrawnSlot == null) {
            return;
        }
        // 清空本场已提交结果(视同重启对决,替换者从零开始)
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId)
                .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        // 队列下一位(排除弃权者与场上对手)
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stageId);
        Long otherFinal = otherId;
        Long replacement = queue.stream()
            .filter(id -> !id.equals(withdrawnId) && (otherFinal == null || !id.equals(otherFinal)))
            .findFirst().orElse(null);
        if (Long.valueOf(1L).equals(withdrawnSlot)) {
            // 擂主弃权:对手自动变擂主(slot1),队列下一位顶上来挑战(slot2)
            if (otherId == null) {
                participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 1L));
            } else {
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, otherId)
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 1L));
                if (replacement != null) {
                    participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                        .set(TMatchParticipant::getCompetitorId, replacement)
                        .eq(TMatchParticipant::getMatchId, matchId)
                        .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
                } else {
                    participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                        .eq(TMatchParticipant::getMatchId, matchId)
                        .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
                }
            }
        } else {
            // 挑战者弃权:擂主不动,队列下一位顶上来挑战(slot2)
            if (replacement != null) {
                participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
                    .set(TMatchParticipant::getCompetitorId, replacement)
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
            } else {
                participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                    .eq(TMatchParticipant::getMatchId, matchId)
                    .eq(TMatchParticipant::getDisplaySlotIndex, 2L));
            }
        }
        // 双方回到待判状态
        participantMapper.update(null, Wrappers.<TMatchParticipant>lambdaUpdate()
            .set(TMatchParticipant::getOutcomeStatus, MatchOutcomeEnum.PENDING.getCode())
            .set(TMatchParticipant::getScoreValue, null)
            .eq(TMatchParticipant::getMatchId, matchId));
        log.info("擂台赛[{}]弃权选手[{}](slot{})由[{}]补位(同一场次,对手={})",
            stageId, withdrawnId, withdrawnSlot, replacement, otherId);
    }

    private void insertArenaParticipant(TMatch match, Long competitorId, Long slotIndex) {
        TMatchParticipant p = new TMatchParticipant();
        p.setTournamentId(match.getTournamentId());
        p.setTenantId(match.getTenantId());
        p.setMatchId(match.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(slotIndex);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
    }

    private ArenaOverviewVo.CompetitorInfo toArenaCompetitor(Long competitorId,
                                                             Map<Long, TCompetitor> compMap,
                                                             Map<Long, String> avatars,
                                                             Map<Long, Integer> points,
                                                             int queueIndex) {
        TCompetitor c = compMap.get(competitorId);
        ArenaOverviewVo.CompetitorInfo ci = new ArenaOverviewVo.CompetitorInfo();
        ci.setCompetitorId(competitorId);
        ci.setName(c != null ? c.getName() : null);
        ci.setNumber(c != null ? c.getNumber() : null);
        ci.setAvatar(avatars.get(competitorId));
        ci.setPoints(points.getOrDefault(competitorId, 0));
        ci.setQueueIndex(queueIndex);
        return ci;
    }

    /** 参赛方首张照片:competitor → member → player.avatar */
    private Map<Long, String> loadAvatarMap(List<Long> compIds) {
        Map<Long, String> map = new HashMap<>();
        if (compIds == null || compIds.isEmpty()) {
            return map;
        }
        List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .in(TCompetitorMember::getCompetitorId, compIds));
        if (members.isEmpty()) {
            return map;
        }
        List<Long> playerIds = members.stream()
            .map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (playerIds.isEmpty()) {
            return map;
        }
        Map<Long, String> avatars = playerMapper.selectList(Wrappers.<TPlayer>lambdaQuery()
                .in(TPlayer::getId, playerIds))
            .stream()
            .filter(p -> StringUtils.isNotBlank(p.getAvatar()))
            .collect(Collectors.toMap(TPlayer::getId, TPlayer::getAvatar, (a, b) -> a));
        for (TCompetitorMember mem : members) {
            String av = avatars.get(mem.getPlayerId());
            if (StringUtils.isNotBlank(av)) {
                map.putIfAbsent(mem.getCompetitorId(), av);
            }
        }
        return map;
    }
}
