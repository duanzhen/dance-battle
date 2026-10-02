package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import com.dance.street.game.service.impl.flow.StageLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自由对抗赛段的写操作:手动加场、删场、手动选晋级。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者(不对外暴露接口,
 * 仍由 {@code ITStageLifecycleService} 转发)。自由对抗没有自动对阵,所有对阵与晋级
 * 都由导播在手机端手点,所以这块逻辑自成一条业务线。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class FreeMatchService {

    private final StageLookup stageLookup;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TCompetitorMapper competitorMapper;
    private final TRoundScoreMapper roundScoreMapper;
    /** 赛段级结果(晋级/淘汰/名次)的唯一写入口 */
    private final CompetitorOutcomeWriter outcomeWriter;
    private final ITStageRosterService rosterService;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;

    /** 手动加一场对战(自由对抗):只有进行中的赛段能加,选手须属于本赛段且未退赛。 */
    @Transactional(rollbackFor = Exception.class)
    public Long createFreeMatch(Long stageId, Long competitorAId, Long competitorBId) {
        TStage stage = stageLookup.get(stageId);
        requireFreeMatchStage(stage);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段尚未开始,无法添加对战;请先在导播台点「开始赛段」");
        }
        if (competitorAId == null || competitorBId == null) {
            throw new ServiceException("请选择两名对战的选手");
        }
        if (competitorAId.equals(competitorBId)) {
            throw new ServiceException("同一名选手不能与自己对战");
        }
        TCompetitor a = requireStageCompetitor(stageId, competitorAId);
        TCompetitor b = requireStageCompetitor(stageId, competitorBId);

        long count = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId));
        TMatch m = new TMatch();
        m.setTournamentId(stage.getTournamentId());
        m.setTenantId(stage.getTenantId());
        m.setStageId(stageId);
        m.setName("第" + (count + 1) + "场");
        m.setDisplayRow(count + 1);
        m.setDisplayCol(1L);
        m.setDisplayZone("CENTER");
        m.setStatus(StageConstants.MATCH_PENDING);
        m.setMatchMode(MatchModeEnum.STANDARD.getCode());
        m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
        matchMapper.insert(m);

        TMatchRound round = new TMatchRound();
        round.setTournamentId(stage.getTournamentId());
        round.setTenantId(stage.getTenantId());
        round.setMatchId(m.getId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_PENDING);
        matchRoundMapper.insert(round);

        insertFreeMatchParticipant(m, a.getId(), 0L);
        insertFreeMatchParticipant(m, b.getId(), 1L);

        log.info("自由对抗赛段[{}]手动添加第{}场对战:{} vs {}", stageId, count + 1, a.getName(), b.getName());
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, m.getId(), "match");
        return m.getId();
    }

    /** 删除一场对战(自由对抗):已结算的不能删,需先重置该场。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFreeMatch(Long matchId) {
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在");
        }
        TStage stage = stageLookup.get(match.getStageId());
        requireFreeMatchStage(stage);
        if (StageConstants.MATCH_SETTLED.equals(match.getStatus())) {
            throw new ServiceException("已结算的对战不能删除;如需重来请先重置该场");
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, matchId).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().eq(TMatchParticipant::getMatchId, matchId));
        matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().eq(TMatchRound::getMatchId, matchId));
        matchMapper.deleteById(matchId);
        log.info("自由对抗赛段[{}]删除对战场次[{}]", stage.getId(), matchId);
        refereeSseNotifier.notifyStage(stage.getId(), "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    /** 手动选晋级(自由对抗):选中的按传入顺序排名次并标记晋级,其余淘汰,退赛选手不动。 */
    @Transactional(rollbackFor = Exception.class)
    public int selectFreeMatchAdvancers(Long stageId, List<Long> competitorIds) {
        TStage stage = stageLookup.get(stageId);
        requireFreeMatchStage(stage);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段未在进行中,无法选择晋级者");
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .orderByAsc(TCompetitor::getNumber)
            .orderByAsc(TCompetitor::getId));
        if (comps.isEmpty()) {
            throw new ServiceException("赛段暂无参赛选手");
        }
        Set<Long> selected = competitorIds == null ? Set.of() : new LinkedHashSet<>(competitorIds);
        Set<Long> stageIds = comps.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        for (Long cid : selected) {
            if (cid == null || !stageIds.contains(cid)) {
                throw new ServiceException("选手[{}]不属于本赛段", cid);
            }
        }
        // 记录对战结果之外只做两件事:选中的标记晋级(名次按传入顺序),其余标记淘汰;退赛选手保持不动
        long rank = 1L;
        int advanced = 0;
        for (TCompetitor c : comps) {
            if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                continue;
            }
            boolean advance = selected.contains(c.getId());
            outcomeWriter.writeResult(c.getId(),
                advance ? OutcomeStatusEnum.ADVANCE.getCode() : OutcomeStatusEnum.ELIMINATED.getCode(),
                advance ? rank : null);
            if (advance) {
                rank++;
                advanced++;
            }
        }
        log.info("自由对抗赛段[{}]手动选定晋级 {} 人:{}", stageId, advanced, selected);
        // 手工选定晋级 = 上游重算:下游中间层全量重建
        rosterService.rebuildEntriesOfDownstream(stageId);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return advanced;
    }

    /** 校验赛段为自由对抗模式 */
    private void requireFreeMatchStage(TStage stage) {
        if (!StageModeEnum.FREE_MATCH.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅自由对抗赛段支持手动添加对战/选择晋级");
        }
    }

    /** 取本赛段参赛方,并排除已退赛选手 */
    private TCompetitor requireStageCompetitor(Long stageId, Long competitorId) {
        TCompetitor c = competitorMapper.selectById(competitorId);
        if (c == null || !Objects.equals(c.getStageId(), stageId)) {
            throw new ServiceException("选手不存在或不属于本赛段");
        }
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
            throw new ServiceException("选手[{}]已退赛,无法安排对战", c.getName());
        }
        return c;
    }

    /** 自由对战场次的参赛方行 */
    private void insertFreeMatchParticipant(TMatch match, Long competitorId, Long slotIndex) {
        TMatchParticipant p = new TMatchParticipant();
        p.setTournamentId(match.getTournamentId());
        p.setTenantId(match.getTenantId());
        p.setMatchId(match.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(slotIndex);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);
    }
}
