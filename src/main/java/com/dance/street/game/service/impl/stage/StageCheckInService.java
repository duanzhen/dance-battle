package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfile.Setup.Trait;
import com.dance.street.game.engine.common.StageModeProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dance.street.game.service.impl.settle.SettlementSupport.parseCompetitorNumber;

/**
 * 海选/排名赛的签到落圈:补签到、改圈改号、解除签到。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者。逐选手赛制(AUDITION/RANK)
 * 每个选手占一个独立轮次,所以"往圈里加一个人"不是简单插一行:要按号码插入对应位置、
 * 把插入点之后的槽位与轮次序号整体顺延。这块与圈结构维护分开,各自独立。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StageCheckInService {

    private final StageLookup stageLookup;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TCompetitorMapper competitorMapper;
    /** 圈场次口径(签到只落进已有的圈) */
    private final AuditionCircleSupport auditionCircleSupport;
    /** 加赛场次判定(加赛只允许同分选手参与,不追加新人) */
    private final SettlementSupport settlementSupport;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final RefereeSseNotifier refereeSseNotifier;

    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId) {
        appendStageCompetitor(stageId, competitorId, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        appendStageCompetitor(stageId, competitorId, targetMatchId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId, Integer zoneIndex) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = stageLookup.get(stageId);
        // 海选/排名赛均为逐选手轮次:签到/补签到选手直接挂入未结算圈场次,可被裁判打分。
        // 已生成对阵但尚未开赛(PENDING)时同样挂入,否则补签到选手会从打分中"消失"
        // (不参与任何场次,结算后无晋级/淘汰结果,且无任何提示)。
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        boolean perCompetitor = profile.result().perCompetitor();
        if (!perCompetitor) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }

        // 海选圈必须先建好(ensure-circle-slots / generate-matches),签到只负责把人落进已有的圈。
        // 这里不再按配置补建圈场次——「没有圈也能签到」会让选手静默不参与打分与结算。
        boolean auditionSplit = profile.setup().has(Trait.CIRCLE_SPLIT);
        if (auditionSplit && auditionCircleSupport.circles(stage).isEmpty()) {
            throw new ServiceException("海选赛段尚未建立圈场次,请先创建圈后再签到");
        }

        // 逐选手模式(AUDITION/RANK)补签到窗口:进行中或规划中均可挂入;
        // 已生成对阵但尚未开赛(DRAFT)同样允许挂入,否则迟到者会从打分中"消失"。
        // 非逐选手赛制(淘汰/小组/擂台)仍保持原语义,不在生成后追加参赛方。
        boolean attachable = StageConstants.STAGE_GAMING.equals(stage.getStatus())
            || (StageConstants.STAGE_DRAFT.equals(stage.getStatus())
                && (auditionSplit || perCompetitor));
        if (!attachable) {
            return;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            // 尚无场次:后续生成对阵(自动补救)时会纳入该参赛方,无需处理
            return;
        }
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        long existed = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .eq(TMatchParticipant::getCompetitorId, competitorId));
        if (existed > 0) {
            return;
        }
        // 仅可挂入未结算的正式圈场次(加赛只允许同分选手参与,不追加新人)
        List<TMatch> candidates = matches.stream()
            .filter(m -> !StageConstants.MATCH_SETTLED.equals(m.getStatus()))
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
        if (candidates.isEmpty()) {
            if (auditionSplit) {
                throw new ServiceException("海选没有可落圈的圈场次(圈已结算或正在进行加赛),无法签到");
            }
            return;
        }
        TMatch target;
        if (targetMatchId != null) {
            // 目标圈须为未结算的正式圈(加赛场次不可追加新人)
            target = candidates.stream()
                .filter(m -> m.getId().equals(targetMatchId))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                    "目标圈场次不存在、已结算或为加赛场次,无法加入;请选择其他圈"));
        } else if (zoneIndex != null) {
            // 客户端只给了圈序号(尚未拿到场次ID时):第 k 圈 = 本赛段第 k 个正式圈场次
            // (candidates 已按 displayRow、id 升序,与 auditionCircles 同一顺序)
            if (zoneIndex <= 0 || candidates.size() < zoneIndex) {
                throw new ServiceException("目标圈[第{}圈]不存在或不可用", zoneIndex);
            }
            target = candidates.get(zoneIndex - 1);
        } else {
            // 落圈一律由客户端决定:后端不再按号码/名额择优推导,
            // 否则同一批号码会因「先建圈后签到 / 签完再生成」等调用顺序不同而落到不同的圈。
            throw new ServiceException("请指定落圈:补签到必须传入目标圈场次ID或圈序号");
        }
        appendParticipantWithRound(target, competitorId);
        log.info("海选/排名赛段[{}]补签到:参赛方[{}]挂入场次[{}]", stageId, competitorId, target.getId());
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
        // 开赛后的补签到:必须推给该圈裁判,否则裁判端横向选手列表里看不到刚加的人
        // (前端收到后会重新拉取名单;只推赛事事件通道的话手机端收不到)
        refereeSseNotifier.notifyMatch(stageId, target.getId(), "competitors");
    }

    /** 改圈/改号:把参赛方从原圈移到目标圈(不指定目标圈则只在原圈内按新号码重排)。 */
    @Transactional(rollbackFor = Exception.class)
    public void relocateCheckInCompetitor(Long stageId, Long competitorId, Long targetMatchId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = stageLookup.get(stageId);
        if (!StageModeProfiles.of(stage.getStageMode()).result().perCompetitor()) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法修改签到结果");
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            // 尚未生成对阵:号码更新由后续生成对阵统一纳入
            return;
        }
        // 该参赛方当前所在的圈场次一次查回(此前按场次逐个 selectCount)
        List<Long> matchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        Set<Long> attachedMatchIds = matchIds.isEmpty() ? Set.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .eq(TMatchParticipant::getCompetitorId, competitorId)
                    .select(TMatchParticipant::getMatchId))
                .stream().map(TMatchParticipant::getMatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        TMatch source = matches.stream()
            .filter(m -> attachedMatchIds.contains(m.getId()))
            .findFirst().orElse(null);
        if (source == null) {
            // 未挂入场次(异常数据兜底):直接按新号码/目标圈补挂,避免新号码不参与场次
            appendStageCompetitor(stageId, competitorId, targetMatchId);
            return;
        }

        TMatch target;
        if (targetMatchId != null) {
            target = matches.stream().filter(m -> m.getId().equals(targetMatchId))
                .findFirst().orElse(null);
            if (target == null) {
                throw new ServiceException("目标圈场次不存在");
            }
            if (StageConstants.MATCH_SETTLED.equals(target.getStatus())
                || settlementSupport.isTiebreaker(target)) {
                throw new ServiceException("目标圈场次已结算或为加赛场次,无法改入");
            }
        } else {
            // 未指定目标圈:保持原圈,仅在圈内按新号码重排。
            // 需要换圈时由客户端显式传入 targetMatchId——后端不再按号码推导圈位。
            target = source;
        }

        removeParticipantWithRound(source, competitorId);
        appendParticipantWithRound(target, competitorId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, target.getId(), "stage");
        refereeSseNotifier.notifyMatch(stageId, target.getId(), "competitors");
        if (source.getId() != null && !source.getId().equals(target.getId())) {
            refereeSseNotifier.notifyMatch(stageId, source.getId(), "competitors");
        }
        log.info("海选/排名赛段[{}]编辑签到:参赛方[{}]从场次[{}]改入场次[{}]",
            stageId, competitorId, source.getId(), target.getId());
    }

    /** 解除签到:把参赛方从各个圈场次移除。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeCheckInCompetitor(Long stageId, Long competitorId) {
        if (stageId == null || competitorId == null) {
            return;
        }
        TStage stage = stageLookup.get(stageId);
        if (!StageModeProfiles.of(stage.getStageMode()).result().perCompetitor()) {
            return;
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段已结束,无法解除签到");
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        // 一次查回该参赛方所在的全部圈场次,替代逐场 selectCount
        List<Long> matchIds = matches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        Set<Long> attachedMatchIds = matchIds.isEmpty() ? Set.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .eq(TMatchParticipant::getCompetitorId, competitorId)
                    .select(TMatchParticipant::getMatchId))
                .stream().map(TMatchParticipant::getMatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean removed = false;
        for (TMatch m : matches) {
            if (attachedMatchIds.contains(m.getId())) {
                removeParticipantWithRound(m, competitorId);
                removed = true;
            }
        }
        if (removed) {
            tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
            refereeSseNotifier.notifyStage(stageId, "competitors");
            log.info("海选/排名赛段[{}]解除签到:参赛方[{}]已移出圈场次", stageId, competitorId);
        }
    }

    /**
     * 从场次移除参赛方及其独立轮次:已有打分记录时禁止移除;
     * 移除后按号码顺序重排本场 participant 槽位与轮次序号。
     */
    private void removeParticipantWithRound(TMatch match, Long competitorId) {
        TMatchRound round = matchRoundMapper.selectOne(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())
            .eq(TMatchRound::getCompetitorId, competitorId)
            .last("limit 1"));
        if (round != null) {
            long scored = roundScoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId()));
            if (scored > 0) {
                throw new ServiceException(
                    "该选手在「{}」已有打分记录,无法修改/解除签到,请先处理该场次成绩", match.getName());
            }
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                .eq(TRoundScore::getRoundId, round.getId()));
            matchRoundMapper.deleteById(round.getId());
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, competitorId));
        renumberMatchParticipants(match.getId());
    }

    /**
     * 按号码顺序重建场次内的展示位与轮次序号(移除/改号后保持 1..n 连续)。
     */
    private void renumberMatchParticipants(Long matchId) {
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, matchId)
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        if (parts.isEmpty()) {
            return;
        }
        Map<Long, TCompetitor> compById = competitorMapper.selectByIds(parts.stream()
                .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        parts.sort(Comparator
            .comparingInt((TMatchParticipant p) -> {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                return c == null ? Integer.MAX_VALUE : parseCompetitorNumber(c.getNumber());
            })
            .thenComparingLong(TMatchParticipant::getId));

        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, matchId)
            .orderByAsc(TMatchRound::getRoundSequence));
        Map<Long, List<TMatchRound>> roundsByComp = rounds.stream()
            .filter(r -> r.getCompetitorId() != null)
            .collect(Collectors.groupingBy(TMatchRound::getCompetitorId));

        for (int i = 0; i < parts.size(); i++) {
            TMatchParticipant p = parts.get(i);
            long targetSlot = i + 1L;
            // 只写真的变了的那几行:补签到常见的是"插到末尾",此时前面几十号人
            // 的槽位一字未动,不该跟着写一遍(36 人圈 = 36 条无谓 UPDATE)
            if (p.getDisplaySlotIndex() == null || p.getDisplaySlotIndex() != targetSlot) {
                TMatchParticipant slotUpd = new TMatchParticipant();
                slotUpd.setId(p.getId());
                slotUpd.setDisplaySlotIndex(targetSlot);
                participantMapper.updateById(slotUpd);
            }
            List<TMatchRound> own = roundsByComp.getOrDefault(p.getCompetitorId(), List.of());
            if (!own.isEmpty()) {
                TMatchRound round = own.get(0);
                if (round.getRoundSequence() == null || round.getRoundSequence() != targetSlot) {
                    TMatchRound rUpd = new TMatchRound();
                    rUpd.setId(round.getId());
                    rUpd.setRoundSequence(targetSlot);
                    matchRoundMapper.updateById(rUpd);
                }
            }
        }
    }

    /**
     * 追加参赛方并新建轮次(海选补签到:每个参赛方一个独立轮次,裁判逐选手打分)。
     * 海选/排名赛按号码数值排序上场:补签选手按其号码插入对应位置,
     * 插入点之后的参赛方(slot)与轮次(round)统一顺延 +1,避免新选手被追加到队尾导致号码排序错位。
     */
    private void appendParticipantWithRound(TMatch target, Long competitorId) {
        TCompetitor newcomer = competitorId == null ? null : competitorMapper.selectById(competitorId);
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, target.getId())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        Map<Long, TCompetitor> compById = parts.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(parts.stream()
                    .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        // 插入位置:号码数值小于新选手的参赛方数量(同号排在已有同号之后)
        int insertIdx = 0;
        if (newcomer != null) {
            for (TMatchParticipant p : parts) {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                if (c == null || parseCompetitorNumber(c.getNumber()) < parseCompetitorNumber(newcomer.getNumber())) {
                    insertIdx++;
                }
            }
        } else {
            insertIdx = parts.size();
        }
        long newSlot = insertIdx + 1L;
        long newRound = insertIdx + 1L;

        // 插入点及之后的参赛方 slot 顺延 +1
        if (newcomer != null) {
            for (TMatchParticipant p : parts) {
                TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                if (c != null
                    && parseCompetitorNumber(c.getNumber()) >= parseCompetitorNumber(newcomer.getNumber())) {
                    TMatchParticipant upd = new TMatchParticipant();
                    upd.setId(p.getId());
                    upd.setDisplaySlotIndex((p.getDisplaySlotIndex() == null ? 0L : p.getDisplaySlotIndex()) + 1L);
                    participantMapper.updateById(upd);
                }
            }
            // 插入点及之后的轮次 sequence 顺延 +1(每个参赛方一个独立轮次,按 competitorId 对齐)
            Set<Long> shiftRoundCompetitorIds = parts.stream()
                .filter(p -> {
                    TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
                    return c != null
                        && parseCompetitorNumber(c.getNumber()) >= parseCompetitorNumber(newcomer.getNumber());
                })
                .map(TMatchParticipant::getCompetitorId)
                .collect(Collectors.toSet());
            List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .eq(TMatchRound::getMatchId, target.getId())
                .orderByAsc(TMatchRound::getRoundSequence));
            for (TMatchRound r : rounds) {
                if (r.getCompetitorId() != null && shiftRoundCompetitorIds.contains(r.getCompetitorId())) {
                    TMatchRound upd = new TMatchRound();
                    upd.setId(r.getId());
                    upd.setRoundSequence((r.getRoundSequence() == null ? 0L : r.getRoundSequence()) + 1L);
                    matchRoundMapper.updateById(upd);
                }
            }
        }

        TMatchParticipant p = new TMatchParticipant();
        p.setTenantId(target.getTenantId());
        p.setTournamentId(target.getTournamentId());
        p.setMatchId(target.getId());
        p.setCompetitorId(competitorId);
        p.setDisplaySlotIndex(newSlot);
        p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(p);

        // 每个参赛方一个独立轮次,裁判逐选手打分
        TMatchRound round = new TMatchRound();
        round.setTenantId(target.getTenantId());
        round.setTournamentId(target.getTournamentId());
        round.setMatchId(target.getId());
        round.setRoundSequence(newRound);
        round.setCompetitorId(competitorId);
        round.setStatus(target.getStatus());
        matchRoundMapper.insert(round);

        log.info("参赛方[{}]挂入场次[{}](slot={},round={})", competitorId, target.getId(), newSlot, newRound);
    }
}
