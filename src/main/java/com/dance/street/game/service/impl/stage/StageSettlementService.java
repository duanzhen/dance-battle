package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.vo.StageCompleteVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.scoring.ScoreAggregator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.CompetitorOutcomeWriter;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import com.dance.street.game.service.impl.settle.StageSettlerRegistry;
import com.dance.street.game.engine.common.StageModeProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 赛段结算与晋级:完成赛段、撤销重来、把晋级者装配进下游。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者。完成赛段本身只做三件事
 * ——选结算策略、置状态、广播(策略在 settle 包,每种赛制一个类);撤销与晋级则围绕
 * "名单来源边"展开:撤销必须先撤下游,晋级要喂给所有引用了本赛段的赛段(分支场景不只一条边)。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StageSettlementService {

    private final StageLookup stageLookup;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    /** 赛段级结果(晋级/淘汰/名次)的唯一写入口 */
    private final CompetitorOutcomeWriter outcomeWriter;
    private final TTournamentMapper tournamentMapper;
    /** 结算策略注册表:按赛制分派(见 settle 包) */
    private final StageSettlerRegistry settlerRegistry;
    /** 名单服务:中间层重建、来源边关系与快照回退 */
    private final ITStageRosterService rosterService;
    /** 赛段链唯一入口:确认晋级时推导上一赛段 */
    private final com.dance.street.game.service.impl.StageChain stageChain;
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;

    /** 完成赛段:GAMING → SETTLED;不能结束时用返回值表达(二海加赛 / 仍有场次未结算)。 */
    @Transactional(rollbackFor = Exception.class)
    public StageCompleteVo completeStage(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageConstants.STAGE_GAMING.equals(stage.getStatus())) {
            throw new ServiceException("仅 GAMING 状态的赛段可完成,当前: {}", stage.getStatus());
        }
        // 按赛制选结算策略(见 settle 包):每种赛制的结算规则独立成类,
        // 这里只负责"选策略 → 置状态 → 广播"。
        StageSettleOutcome outcome = settlerRegistry.of(stage.getStageMode()).settle(stage);
        if (!outcome.closable()) {
            // 结算产生了后续工作(二海加赛、仍有场次未结算…):赛段保持进行中。
            // 「还不能结束」统一用返回值表达,不再与异常混用——导播台只需看 message。
            log.info("赛段[{}]暂不能结束:{}", stageId, outcome.reason());
            // 同分加赛单独标记:前端据此弹「需要加赛」,其余原因仍走普通提示。
            return outcome.tiebreaker()
                ? StageCompleteVo.pendingTiebreaker(outcome.reason())
                : StageCompleteVo.pending(outcome.reason());
        }
        stage.setStatus(StageConstants.STAGE_SETTLED);
        stageMapper.updateById(stage);
        // 上游结算完成:重建所有引用本赛段的下游中间层名单(上游一变就全部重新来)
        rosterService.rebuildEntriesOfDownstream(stageId);
        // 上游结束了:下游对阵里那些"等上游填入"的座位,此刻没人来就是真轮空(待定→轮空)
        rosterService.settlePendingSeatsOfDownstream(stageId);
        // 名单就绪度由源结算状态推导,结算完成无需推进任何状态;
        // 下游开赛守卫与 apply 都会现场按源状态计算。
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        // 不再由后端自动确认晋级:完成赛段仅产出晋级预排;是否跳过中间态由 MC 导播台
        // 在开始下一赛段时弹窗确认后调用「确认晋级」接口决定(见 isAutoConfirmAdvancement)
        return StageCompleteVo.settled();
    }

    /**
     * 赛事级配置:是否开启「跳过中间态确认阶段」(themeConfig.autoConfirmAdvancement,默认开启)。
     * 开启后 MC 导播台在开始赛段时弹窗确认,调用确认晋级接口跳过中间态直接开始。
     */
    public boolean isAutoConfirmAdvancement(Long tournamentId) {
        if (tournamentId == null) {
            return false;
        }
        TTournament t = tournamentMapper.selectById(tournamentId);
        if (t == null || StringUtils.isBlank(t.getThemeConfig())) {
            return true;
        }
        try {
            return cn.hutool.json.JSONUtil.parseObj(t.getThemeConfig())
                .getBool("autoConfirmAdvancement", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** 撤销赛段数据,退回"中间态还没确认",可重新调整后再次确认名单。 */
    @Transactional(rollbackFor = Exception.class)
    public void resetStageToDraft(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("已取消的赛段不能撤销数据");
        }
        assertDownstreamRevertible(stage);
        // 入口赛段(海选/排名赛等第一个赛段)没有中间态:名单就是签到进来的人,
        // 撤销时人一个都不能动,只清比赛与判罚数据。
        boolean entryStage = rosterService.groupsOfStage(stageId).stream()
            .noneMatch(g -> g.getSourceStageId() != null);
        // 级联清除场次/轮次/参赛明细/打分
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId));
        if (!matches.isEmpty()) {
            List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds)
                    .select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds));
            }
            participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds));
            matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds));
            // 场次裁判也必须删:留着孤儿行,重新生成对阵后裁判名单会越积越多
            matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
                .in(TMatchReferee::getMatchId, matchIds));
            matchMapper.deleteByIds(matchIds);
        }
        if (!entryStage) {
            // 名单快照:删除 apply 写入的行(from_roster=1)及其成员,名单 applied 回退,可重新装配;
            // 保留签到/手工 GUEST 等非快照行按旧语义回退待定
            List<TCompetitor> snapshotRows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getFromRoster, 1L));
            if (!snapshotRows.isEmpty()) {
                List<Long> snapshotRowIds = snapshotRows.stream().map(TCompetitor::getId).toList();
                competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                    .in(TCompetitorMember::getCompetitorId, snapshotRowIds));
                competitorMapper.deleteByIds(snapshotRowIds);
            }
            // 退回"中间态还没确认":只翻状态位,中间层的行(含人工调整)原样留着当重新确认的起点
            rosterService.resetByTarget(stageId);
        }
        // 参赛方回退未开始(保留种子位,可重新 setSeedOrder/initialize)
        competitorMapper.update(null, Wrappers.<TCompetitor>lambdaUpdate()
            .eq(TCompetitor::getStageId, stageId)
            .set(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode())
            .set(TCompetitor::getFinalRank, null));
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setIsInitialized(0L);
        upd.setStatus(StageConstants.STAGE_DRAFT);
        stageMapper.updateById(upd);
        // 参赛方已回退待定/名次清空:下游中间层里"按旧结果落座的人"必须一起还原成空位,
        // 否则重置后中间态还挂着已经不算数的人(人工加进来的行保持不动)。
        rosterService.syncPreAdvance(stageId);
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        log.info("赛段[{}]已撤销数据:清除{}场对阵及轮次/明细/裁判/打分,{}",
            stageId, matches.size(), entryStage ? "入口赛段参赛方保留" : "退回中间态未确认");
    }

    /**
     * 撤销必须从后往前:本赛段的晋级结果一旦被下游"确认名单 / 生成对阵 / 开赛"消费过,
     * 先撤本赛段会把下游留在一批不再成立的选手上。这里直接拦住并点名下游赛段。
     *
     * <p>下游只是"规划中且还没确认名单"时不用拦:重置末尾的
     * {@code syncPreAdvance} 会把从本赛段来的人还原成空位。</p>
     */
    private void assertDownstreamRevertible(TStage stage) {
        List<Long> targets = rosterService.listBySource(stage.getId()).stream()
            .map(TStageRosterVo::getTargetStageId)
            .filter(Objects::nonNull)
            .filter(id -> !Objects.equals(id, stage.getId()))
            .distinct()
            .toList();
        if (targets.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        for (TStage target : stageMapper.selectByIds(targets)) {
            boolean consumed = !StageConstants.STAGE_DRAFT.equals(target.getStatus())
                || Long.valueOf(1L).equals(target.getRosterApplied())
                || Long.valueOf(1L).equals(target.getRosterSkipped())
                || matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, target.getId())) > 0;
            if (consumed) {
                blocked.add(target.getName());
            }
        }
        if (!blocked.isEmpty()) {
            // 撤销只能从后往前:后面的赛段还在进行中/已结束时,先撤它,再撤本赛段
            throw new ServiceException("下游赛段[{}]已确认名单或已开赛,请先撤销它再撤销本赛段",
                String.join("、", blocked));
        }
    }

    /** 确认晋级:把已结算赛段的晋级者装配进所有引用它的下游赛段(名单整单装配的唯一内核)。 */
    @Transactional(rollbackFor = Exception.class)
    public int calculateAdvancement(Long stageId) {
        // 导播台「跳过中间态确认」入口:统一委托名单整单装配(唯一写库内核)
        TStage stage = stageLookup.get(stageId);
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅 SETTLED 状态的赛段可计算晋级");
        }
        // 晋级者写进"所有引用了本赛段的赛段":依赖以来源组(边)为准,不再只写链上的下一个。
        // 分支场景下有多条下游,跳过中间态就应当把每条都喂上。
        int total = 0;
        for (Long targetId : referencingTargetIds(stageId)) {
            if (stageMapper.selectById(targetId) == null) {
                continue;
            }
            total += rosterService.applyRoster(targetId, null);
        }
        return total;
    }

    /** 引用了本赛段(即"本赛段的人会流进去")的目标赛段 ID */
    private List<Long> referencingTargetIds(Long sourceStageId) {
        return rosterService.listBySource(sourceStageId).stream()
            .map(TStageRosterVo::getTargetStageId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /** 导播台「确认晋级」:装配本赛段上一赛段(按 next 链推导)的晋级者。 */
    @Transactional(rollbackFor = Exception.class)
    public int confirmAdvancementOfPreviousStage(Long stageId) {
        // 导播台点「确认晋级」时手里是"要开始的那个赛段",而要装配的是它的上一段
        // (已结算的源赛段)。上一段由 next 链推导,prev 列只是展示字段。
        TStage stage = stageLookup.get(stageId);
        TStage prev = stageChain.prevOf(stage);
        if (prev == null) {
            throw new ServiceException("该赛段没有上一赛段,无需确认晋级");
        }
        return calculateAdvancement(prev.getId());
    }

    /** 排名赛/小组赛:手动决定同分者谁晋级(已接收晋级者的下游会被拦下)。 */
    @Transactional(rollbackFor = Exception.class)
    public int adjustAdvancement(Long stageId, List<Long> competitorIds) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeProfiles.of(stage.getStageMode()).view().advancementAdjustment()) {
            throw new ServiceException("仅排名赛/小组赛赛段支持手动调整同分晋级");
        }
        if (!StageConstants.STAGE_SETTLED.equals(stage.getStatus())) {
            throw new ServiceException("仅已结算(SETTLED)的排名赛赛段可调整同分晋级");
        }
        // 幂等:只要有任意下游赛段已经接收了本赛段的晋级者,就不允许再调整
        // (依赖以来源组为准,分支场景下要逐个检查,不能只看链上的下一个)
        for (Long nextStageId : referencingTargetIds(stageId)) {
            long existed = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, nextStageId)
                .isNotNull(TCompetitor::getSourceCompetitorId));
            if (existed > 0) {
                TStage downstream = stageMapper.selectById(nextStageId);
                throw new ServiceException("下游赛段[{}]已接收晋级者,无法再调整同分晋级",
                    downstream == null ? nextStageId : downstream.getName());
            }
        }
        List<TCompetitor> pending = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
        if (pending.isEmpty()) {
            throw new ServiceException("当前无待定(同分)参赛者需要调整");
        }
        if (competitorIds == null || competitorIds.isEmpty()) {
            throw new ServiceException("请指定要晋级的参赛者(全部待定者都传入即全部晋级)");
        }
        Set<Long> pendingIds = pending.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        for (Long cid : competitorIds) {
            if (cid == null || !pendingIds.contains(cid)) {
                throw new ServiceException("参赛者[{}]不在本赛段待定名单中", cid);
            }
        }

        // 已有晋级者的最大 finalRank 之后顺延分配
        long nextRank = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode())
                .select(TCompetitor::getFinalRank))
            .stream().mapToLong(c -> c.getFinalRank() == null ? 0L : c.getFinalRank())
            .max().orElse(0L) + 1L;

        Set<Long> advanceSet = new HashSet<>(competitorIds);
        for (Long cid : competitorIds) {
            markManualAdvanceResult(cid, OutcomeStatusEnum.ADVANCE.getCode(), nextRank++, stageId);
        }
        // 未选中的待定者淘汰
        for (TCompetitor c : pending) {
            if (!advanceSet.contains(c.getId())) {
                markManualAdvanceResult(c.getId(), OutcomeStatusEnum.ELIMINATED.getCode(), nextRank++, stageId);
            }
        }
        log.info("排名赛赛段[{}]手动调整同分晋级:{}人晋级,{}人淘汰",
            stageId, competitorIds.size(), pending.size() - competitorIds.size());
        // 手工改了晋级结果 = 上游重算:下游中间层全量重建
        rosterService.rebuildEntriesOfDownstream(stageId);
        refereeSseNotifier.notifyStage(stageId, "stage");
        tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        return competitorIds.size();
    }

    /** 手动调整结果:更新 competitor 赛段级状态 + 本赛段全部场次的 participant 状态 */
    private void markManualAdvanceResult(Long competitorId, String outcome, Long finalRank, Long stageId) {
        outcomeWriter.writeResult(competitorId, outcome, finalRank);

        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (!matchIds.isEmpty()) {
            TMatchParticipant pUpd = new TMatchParticipant();
            pUpd.setOutcomeStatus(outcome);
            participantMapper.update(pUpd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .in(TMatchParticipant::getMatchId, matchIds)
                .eq(TMatchParticipant::getCompetitorId, competitorId));
        }
    }

    /** 排名赛名次明细(按圈/维度),MANUAL/BATCH 未公布前隐藏分数。 */
    public RankDetailVo getRankDetail(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeProfiles.of(stage.getStageMode()).view().rankDetail()) {
            throw new ServiceException("仅排名赛赛段支持排名明细");
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        // 公布控制:MANUAL/BATCH 且未结算前隐藏分数与维度分
        boolean hidden = !StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            && rc != null && rc.getPublishMode() != null && !"AUTO".equalsIgnoreCase(rc.getPublishMode());
        AggregateRuleEnum refRule = AggregateRuleEnum.fromCode(
            rc != null && rc.getScoring() != null ? rc.getScoring().getRefereeAggregateRule() : null);
        java.math.BigDecimal trimRatio = rc != null && rc.getScoring() != null ? rc.getScoring().getTrimRatio() : null;
        List<DimensionConfig> dims = rc != null && rc.getScoring() != null ? rc.getScoring().getDimensions() : null;

        List<TMatch> matches = matchMapper.selectList(
            Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stageId)
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId));
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();

        Map<Long, List<TMatchParticipant>> partsByMatch = matchIds.isEmpty() ? Map.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds))
                .stream().collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        List<Long> roundIds = matchIds.isEmpty() ? List.of() : matchRoundMapper.selectList(
                Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        Map<Long, List<TMatchRound>> roundsByMatch = matchIds.isEmpty() ? Map.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds))
                .stream().collect(Collectors.groupingBy(TMatchRound::getMatchId));
        Map<Long, List<TRoundScore>> scoresByRound = roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
                .stream().collect(Collectors.groupingBy(TRoundScore::getRoundId));

        List<Long> compIds = partsByMatch.values().stream()
            .flatMap(List::stream)
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, TCompetitor> compMap = compIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(compIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));

        RankDetailVo vo = new RankDetailVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStatus(stage.getStatus());
        List<RankDetailVo.CircleRank> circles = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            TMatch m = matches.get(i);
            RankDetailVo.CircleRank cr = new RankDetailVo.CircleRank();
            cr.setZone(m.getDisplayZone());
            cr.setTitle(matches.size() > 1 ? "第" + (i + 1) + "圈" : "排名");
            List<RankDetailVo.CompetitorRank> comps = new ArrayList<>();
            for (TMatchParticipant p : partsByMatch.getOrDefault(m.getId(), List.of())) {
                if (p.getCompetitorId() == null) {
                    continue;
                }
                RankDetailVo.CompetitorRank c = new RankDetailVo.CompetitorRank();
                c.setCompetitorId(p.getCompetitorId());
                TCompetitor comp = compMap.get(p.getCompetitorId());
                c.setName(comp != null ? comp.getName() : null);
                c.setNumber(comp != null ? comp.getNumber() : null);
                c.setRankInMatch(p.getRankInMatch());
                c.setScoreValue(hidden ? null : p.getScoreValue());
                if (!hidden) {
                    c.setDimensions(aggregateCompetitorDimensions(
                        m, p.getCompetitorId(), roundsByMatch, scoresByRound, refRule, trimRatio, dims));
                }
                comps.add(c);
            }
            cr.setCompetitors(comps);
            circles.add(cr);
        }
        vo.setCircles(circles);
        return vo;
    }

    /** 聚合某参赛者跨全部轮次(逐选手轮次)的各维度分:按裁判间汇总规则合并多裁判分 */
    private List<RankDetailVo.DimensionScore> aggregateCompetitorDimensions(
            TMatch match, Long competitorId,
            Map<Long, List<TMatchRound>> roundsByMatch,
            Map<Long, List<TRoundScore>> scoresByRound,
            AggregateRuleEnum refRule, java.math.BigDecimal trimRatio,
            List<DimensionConfig> dims) {
        Map<String, List<java.math.BigDecimal>> byDim = new LinkedHashMap<>();
        for (TMatchRound r : roundsByMatch.getOrDefault(match.getId(), List.of())) {
            if (!Objects.equals(r.getCompetitorId(), competitorId)) {
                continue;
            }
            for (TRoundScore s : scoresByRound.getOrDefault(r.getId(), List.of())) {
                if (s.getCompetitorId() == null || s.getScore() == null) {
                    continue;
                }
                String dim = s.getDimension() != null ? s.getDimension() : StageConstants.DIMENSION_MAIN;
                byDim.computeIfAbsent(dim, k -> new ArrayList<>()).add(s.getScore());
            }
        }
        List<RankDetailVo.DimensionScore> result = new ArrayList<>();
        if (dims != null && !dims.isEmpty()) {
            // 按配置维度顺序返回,保证展示稳定
            for (DimensionConfig d : dims) {
                RankDetailVo.DimensionScore ds = new RankDetailVo.DimensionScore();
                ds.setKey(d.getKey());
                ds.setName(d.getName());
                ds.setMaxScore(d.getMaxScore());
                ds.setScore(ScoreAggregator.aggregate(byDim.getOrDefault(d.getKey(), List.of()), refRule, trimRatio));
                result.add(ds);
            }
        } else {
            for (Map.Entry<String, List<java.math.BigDecimal>> e : byDim.entrySet()) {
                RankDetailVo.DimensionScore ds = new RankDetailVo.DimensionScore();
                ds.setKey(e.getKey());
                ds.setName(e.getKey());
                ds.setScore(ScoreAggregator.aggregate(e.getValue(), refRule, trimRatio));
                result.add(ds);
            }
        }
        return result;
    }
}
