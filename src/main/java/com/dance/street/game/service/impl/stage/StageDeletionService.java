package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.SnowflakeJson;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterEntryMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.StageChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 删除名单依赖图中的一个赛段节点。
 *
 * <p>删除不是单纯清理赛段自己的表，而是一次图事务：先固定被删节点的权威后继，
 * 清理派生名单与快照，重写自动来源边，再删除节点拥有的场次/参赛方，最后重排链并重投影
 * 所有受影响的存活目标。非链首赛段被固定边触碰时仍保持“不允许删除”；
 * 链首赛段没有合法跨赛段入边，其固定出口会随空节点一起删除。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StageDeletionService {

    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final TStageRosterEntryMapper rosterEntryMapper;
    private final TVisWidgetMapper visWidgetMapper;
    private final StageChain stageChain;
    private final ITStageRosterService rosterService;
    private final TournamentEventNotifier tournamentEventNotifier;

    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        if (ids.size() > 1) {
            throw new ServiceException("一次只能删除一个赛段,请逐个删除");
        }
        List<TStage> deletingStages = stageMapper.selectList(
            Wrappers.<TStage>lambdaQuery().in(TStage::getId, ids));
        if (deletingStages.isEmpty()) {
            return false;
        }
        TStage deleted = deletingStages.getFirst();
        assertDeletableStage(deleted);

        assertNoChildStages(deleted);
        List<TMatch> deletedMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, deleted.getId()));
        List<Long> matchIds = deletedMatches.stream()
            .map(TMatch::getId).filter(Objects::nonNull).toList();
        assertNoAssignedMatchPeople(matchIds);
        assertNoExternalMatchReferences(deleted, matchIds);

        Long successorId = successorOf(deleted);
        Set<Long> affectedTargets =
            rosterService.detachStageFromRosterGraph(deleted.getId(), successorId);

        deleteMatchData(matchIds);
        deleteStageCompetitors(deleted.getId());
        refereeStageMapper.delete(Wrappers.<TRefereeStage>lambdaQuery()
            .eq(TRefereeStage::getStageId, deleted.getId()));
        boolean removed = stageMapper.deleteById(deleted.getId()) > 0;

        stageChain.realignPointers(deleted.getTournamentId());
        if (deleted.getTournamentId() != null) {
            rosterService.ensureRosterForSurvivors(List.of(deleted.getTournamentId()));
        }
        rosterService.reconcileAfterStageDelete(affectedTargets);
        clearWidgetStageBindings(deleted, matchIds);
        if (deleted.getTournamentId() != null) {
            tournamentEventNotifier.notify(deleted.getTournamentId(), null, null, "stage");
        }
        log.info("赛段[{}]删除完成:后继={},重投影目标={}", deleted.getId(), successorId, affectedTargets);
        return removed;
    }

    /**
     * 删除只允许发生在未开始的空赛段。中间态座位也算赛段内容：
     * 只要有实际人员，就必须先回到空名单状态，不能靠删除静默带走。
     */
    private void assertDeletableStage(TStage stage) {
        if (StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]进行中,不可删除(已承载现场场次与判分数据)",
                stage.getName());
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]已结束,不可删除(已承载赛果数据)", stage.getName());
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("只有未开始的草稿赛段可以删除,赛段[{}]当前状态为[{}]",
                stage.getName(), stage.getStatus());
        }
        long competitors = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId()));
        if (competitors > 0) {
            throw new ServiceException("赛段[{}]已有 {} 个参赛方,不能删除", stage.getName(), competitors);
        }
        long rosterPeople = rosterEntryMapper.selectCount(
            Wrappers.<TStageRosterEntry>lambdaQuery()
                .eq(TStageRosterEntry::getTargetStageId, stage.getId())
                .eq(TStageRosterEntry::getSlotKind, StageConstants.SLOT_PLAYER));
        if (rosterPeople > 0) {
            throw new ServiceException("赛段[{}]的中间态名单已有 {} 人,不能删除",
                stage.getName(), rosterPeople);
        }
    }

    private void assertNoAssignedMatchPeople(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            return;
        }
        long assigned = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getSlotKind, StageConstants.SLOT_PLAYER));
        if (assigned > 0) {
            throw new ServiceException("赛段场次已安排 {} 个参赛人员,不能删除", assigned);
        }
    }

    /**
     * 用重排前的权威链顺序确定后继，而不是直接信任可能已经悬空的 next_stage_id。
     */
    private Long successorOf(TStage deleted) {
        List<TStage> chain = stageChain.orderedChain(deleted.getTournamentId());
        for (int i = 0; i < chain.size(); i++) {
            if (!Objects.equals(chain.get(i).getId(), deleted.getId())) {
                continue;
            }
            return i + 1 < chain.size() ? chain.get(i + 1).getId() : null;
        }
        return null;
    }

    private void assertNoChildStages(TStage deleted) {
        long children = stageMapper.selectCount(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getParentStageId, deleted.getId()));
        if (children > 0) {
            throw new ServiceException("赛段[{}]仍被 {} 个子赛段引用,不能删除", deleted.getName(), children);
        }
    }

    /**
     * 场次内部路由只允许指向本赛段场次。历史上若已有外部引用，删除时必须显式拦截，
     * 避免之后向已删除场次补插参赛明细。
     */
    private void assertNoExternalMatchReferences(TStage deleted, List<Long> deletedMatchIds) {
        if (deletedMatchIds.isEmpty()) {
            return;
        }
        Set<Long> deletedMatches = new HashSet<>(deletedMatchIds);
        List<TMatch> surviving = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getTournamentId, deleted.getTournamentId())
            .ne(TMatch::getStageId, deleted.getId()));
        for (TMatch match : surviving) {
            if (match.getParentMatchId() != null && deletedMatches.contains(match.getParentMatchId())) {
                throw new ServiceException(
                    "场次[{}]仍以被删赛段场次[{}]为父场次,不能删除", match.getName(), match.getParentMatchId());
            }
            Map<String, PromotionTarget> rules = RuleConfigParser.parsePromotionRule(match.getPromotionRule());
            for (PromotionTarget target : rules.values()) {
                if (target != null && target.getTargetMatchId() != null
                    && deletedMatches.contains(target.getTargetMatchId())) {
                    throw new ServiceException(
                        "场次[{}]的晋级去向仍引用被删赛段场次[{}],不能删除",
                        match.getName(), target.getTargetMatchId());
                }
            }
        }
    }

    private void deleteMatchData(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            return;
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds)
                .select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                .in(TRoundScore::getRoundId, roundIds));
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds));
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
            .in(TMatchReferee::getMatchId, matchIds));
        matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery()
            .in(TMatchRound::getMatchId, matchIds));
        matchMapper.deleteByIds(matchIds);
    }

    private void deleteStageCompetitors(Long stageId) {
        List<Long> competitorIds = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .select(TCompetitor::getId))
            .stream().map(TCompetitor::getId).filter(Objects::nonNull).toList();
        if (competitorIds.isEmpty()) {
            return;
        }
        competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
            .in(TCompetitorMember::getCompetitorId, competitorIds));
        competitorMapper.deleteByIds(competitorIds);
    }

    /** 清除场景组件里指向被删赛段或场次的顶层绑定。 */
    private void clearWidgetStageBindings(TStage stage, List<Long> matchIds) {
        if (stage.getTournamentId() == null) {
            return;
        }
        Set<String> removedRefs = new HashSet<>();
        removedRefs.add(String.valueOf(stage.getId()));
        matchIds.forEach(id -> removedRefs.add(String.valueOf(id)));
        List<TVisWidget> widgets = visWidgetMapper.selectList(Wrappers.<TVisWidget>lambdaQuery()
            .eq(TVisWidget::getTournamentId, stage.getTournamentId()));
        ObjectMapper mapper = SnowflakeJson.mapper();
        for (TVisWidget widget : widgets) {
            if (StringUtils.isBlank(widget.getDataConfig())) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = mapper.readValue(widget.getDataConfig(), Map.class);
                if (config == null) {
                    continue;
                }
                boolean changed = false;
                for (String key : List.of("stageId", "targetStageId", "matchId")) {
                    Object value = config.get(key);
                    if (value != null && removedRefs.contains(String.valueOf(value))) {
                        config.put(key, null);
                        changed = true;
                    }
                }
                if (changed) {
                    TVisWidget update = new TVisWidget();
                    update.setId(widget.getId());
                    update.setDataConfig(mapper.writeValueAsString(config));
                    visWidgetMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("赛段删除联动:组件[{}] dataConfig 解析失败,跳过: {}",
                    widget.getId(), e.getMessage());
            }
        }
    }
}
