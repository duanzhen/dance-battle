package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.engine.common.PairingModeResolver;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.SnowflakeJson;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.engine.generator.MatchPlan;
import com.dance.street.game.engine.generator.SlotPlan;
import com.dance.street.game.engine.generator.StageGeneratorFactory;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.stage.hook.StageHooks;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfile.GeneratePolicy;
import com.dance.street.game.engine.common.StageModeProfile.SeedOrder;
import com.dance.street.game.engine.common.StageModeProfile.Setup.Trait;
import com.dance.street.game.engine.common.StageModeProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.dance.street.game.service.impl.settle.SettlementSupport.parseCompetitorNumber;

/**
 * 赛段的初始化与对阵生成。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者,也是那个类里最长的一块
 * (生成对阵内部实现近 300 行)。它必须先于「开赛/赛中」那块搬走 —— 因为 {@code startStage}
 * 会调用 initialize 与 generateMatches,先搬运行侧会形成循环依赖。</p>
 *
 * <p>搬动时保持逐字一致:座位是"位置"不是"出场顺序"、空位是"轮空"还是"待定"取决于
 * 名单来源是否结算、每个座位都落一行(轮空不跳过)等口径与注释都原样保留。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StageSetupService {

    private final StageLookup stageLookup;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TournamentEventNotifier tournamentEventNotifier;
    private final ITStageRosterService rosterService;
    /** 海选圈口径与圈级裁判绑定 */
    private final AuditionCircleSupport auditionCircleSupport;
    private final StageHooks stageHooks;
    private final StageGeneratorFactory generatorFactory;

    @Transactional(rollbackFor = Exception.class)
    public void initialize(InitializeStageBo bo) {
        TStage stage = stageLookup.get(bo.getStageId());
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,不可重复执行");
        }

        List<TCompetitor> comps;
        if (bo.getCompetitorIds() != null && !bo.getCompetitorIds().isEmpty()) {
            comps = competitorMapper.selectByIds(bo.getCompetitorIds());
        } else {
            LambdaQueryWrapper<TCompetitor> q = Wrappers.lambdaQuery();
            q.eq(TCompetitor::getStageId, stage.getId());
            q.eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode());
            comps = competitorMapper.selectList(q);
        }
        if (comps == null || comps.isEmpty()) {
            throw new ServiceException("赛段无可初始化的参赛方");
        }

        // 各赛制的种子排序口径由画像决定(海选/排名按号码,擂台保 seedRank 在前,其余按 seedRank)
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        comps.sort(seedComparator(profile.setup().seedOrder()));
        // 淘汰赛座位是"位置"而不是"出场次序":中间态允许把选手拖到轮空位互换、位置留空即轮空,
        // 因此开赛初始化不再把座位压成 1..n——否则中间态摆好的位置会被整体重排,生成的对阵与中间态对不上。
        // 其余赛制保持原语义(压成 1..n)。种子顺位批量写:此前逐个 updateById(100 人 = 100 条 SQL)
        boolean keepSeats = profile.setup().has(Trait.KEEPS_SEATS);
        Set<Long> usedSeats = new HashSet<>();
        comps.forEach(c -> {
            if (c.getSeedRank() != null) {
                usedSeats.add(c.getSeedRank());
            }
        });
        long nextSeat = 1L;
        List<Map<String, Object>> seedItems = new ArrayList<>(comps.size());
        for (int i = 0; i < comps.size(); i++) {
            TCompetitor c = comps.get(i);
            if (keepSeats) {
                if (c.getSeedRank() != null) {
                    continue; // 保留中间态排好的座位
                }
                while (usedSeats.contains(nextSeat)) {
                    nextSeat++;
                }
                c.setSeedRank(nextSeat);
                usedSeats.add(nextSeat);
            } else {
                c.setSeedRank((long) (i + 1));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("seedRank", c.getSeedRank());
            seedItems.add(item);
        }
        if (!seedItems.isEmpty()) {
            competitorMapper.batchUpdateSeedRank(seedItems);
        }

        stage.setIsInitialized(1L);
        // 业务状态收敛为 规划中(DRAFT) → 进行中(GAMING) → 已结束(SETTLED):
        // 初始化只锁定名单/排种子,不再进入「未开始(PENDING)」中间态
        stageMapper.updateById(stage);
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    @Transactional(rollbackFor = Exception.class)
    public void generateMatches(GenerateMatchesBo bo) {
        generateMatchesInternal(bo, false);
    }

    /**
     * 生成对阵内部实现。
     *
     * @param randomSplit 海选分圈时是否随机抽取(true 时选手随机分到各圈场次)
     */
    private void generateMatchesInternal(GenerateMatchesBo bo, boolean randomSplit) {
        TStage stage = stageLookup.get(bo.getStageId());
        StageModeProfile profile = StageModeProfiles.of(stage.getStageMode());
        // 擂台赛不生成对阵树(由导播逐场创建);自由对抗也不生成(对手线下抽签/指认,场次由导播手动加)
        if (profile.setup().generatePolicy() == GeneratePolicy.REJECT) {
            throw new ServiceException(profile.setup().rejectMessage());
        }
        if (profile.setup().generatePolicy() == GeneratePolicy.SKIP_SILENT) {
            log.info("赛段[{}]不生成对阵,场次由导播台手动添加", stage.getId());
            return;
        }
        // 已结束/已取消的赛段不允许再生成对阵
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]已结束,无法生成对阵", stage.getName());
        }
        // 海选赛/排名赛:逐选手轮次,允许跳过显式初始化(兜底:自动初始化)
        boolean perCompetitorRound = profile.result().perCompetitor();
        if (profile.setup().has(Trait.REQUIRES_CIRCLES) && plannedCircleCount(stage) < 1) {
            throw new ServiceException("海选尚未配置圈,请先在赛段配置中新增至少一圈(人数/裁判/去向)");
        }
        if (!perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段尚未初始化,请先 initialize");
        }
        // 海选赛/排名赛允许跳过显式初始化(兜底:自动初始化)
        if (perCompetitorRound && !Long.valueOf(1L).equals(stage.getIsInitialized())) {
            // 海选已配置圈(单圈也算)但当前无人签到(预建空圈)时跳过自动初始化,
            // 允许抽号前先生成按配置的空圈结构,待签到后再落圈;
            // 其余场景保持原逻辑(初始化会把名单锁定,不改变业务状态)
            boolean emptyPlannedAudition = profile.setup().has(Trait.CIRCLE_SPLIT) && plannedCircleCount(stage) >= 1
                && competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getStageId, stage.getId())
                    .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode())) == 0;
            if (!emptyPlannedAudition) {
                InitializeStageBo initBo = new InitializeStageBo();
                initBo.setStageId(stage.getId());
                initialize(initBo);
            } else {
                log.info("海选赛段[{}]暂无人签到,跳过自动初始化,按配置预建空圈", stage.getId());
            }
        }
        // 未开赛前允许重新生成(圈数/规则变更后重排):已有对阵但全部仍为 PENDING 时,
        // 先记录待清除,待配置校验通过后再清旧重建;已有场次开始则拒绝
        long exist = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery().eq(TMatch::getStageId, stage.getId()));
        boolean hasOldMatches = exist > 0;
        if (hasOldMatches) {
            long started = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .ne(TMatch::getStatus, StageConstants.MATCH_PENDING));
            if (started > 0) {
                throw new ServiceException(
                    "赛段对阵已生成且已有场次开始,无法重新生成;如需重排请先重置为草稿");
            }
        }
        if (StringUtils.isNotBlank(bo.getRuleConfig())) {
            stage.setRuleConfig(bo.getRuleConfig());
            stageMapper.updateById(stage);
        }

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        if (rc != null) {
            rc.setRandomSplit(randomSplit);
        }
        // 赛制专属的生成前校验(海选圈配置与晋级名额等)
        stageHooks.setupHook(stage.getStageMode()).ifPresent(h -> h.validateBeforeGenerate(stage, rc));
        // 海选分圈:持久化本次分圈方式(按号顺序均分 / 随机抽取),
        // 签到/补签需要据此判断新选手应按号码落圈还是按名额均衡落圈
        if (profile.setup().has(Trait.CIRCLE_SPLIT)) {
            persistCircleSplitMode(stage, randomSplit);
        }
        StageModeEnum mode = StageModeEnum.fromCode(stage.getStageMode());
        // 承接上一淘汰赛胜者:按胜者位置顺序配对(SEQUENTIAL),不受本赛段 SEED 配置影响;
        // 从海选赛进入的淘汰赛,未显式配置时默认标准种子对位(1-16、2-15)
        if (profile.setup().has(Trait.RESOLVES_KNOCKOUT_PAIRING) && rc != null && rc.getKnockout() != null) {
            // 首轮配对方式只看本赛段配置(头尾交叉与否是显式选择),不再按来源赛制推断
            rc.getKnockout().setPairingMode(PairingModeResolver.resolve(rc.getKnockout().getPairingMode()));
        }
        String configuredMatchMode = (rc != null && rc.getScoring() != null) ? rc.getScoring().getMatchMode() : null;
        String matchMode = profile.setup().matchMode().resolve(configuredMatchMode, MatchModeEnum.STANDARD.getCode());

        // 按种子顺位取参赛方(海选赛按签到号码顺序)
        LambdaQueryWrapper<TCompetitor> cq = Wrappers.lambdaQuery();
        cq.eq(TCompetitor::getStageId, stage.getId());
        // 退赛选手不进入对阵(初始化时也会被排除,生成时再兜底过滤一次)
        cq.ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode());
        if (perCompetitorRound) {
            cq.orderByAsc(TCompetitor::getNumber);
        } else {
            cq.orderByAsc(TCompetitor::getSeedRank);
        }
        List<TCompetitor> comps = competitorMapper.selectList(cq);
        // 海选/排名赛:按签到号码的数值排序(抽签号码决定上场/落位顺序),
        // 数据库字符串排序在号码不补零时会错位(如 10 < 2),这里统一按数值重排
        if (perCompetitorRound) {
            comps.sort(Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber())));
        }
        // 防御:seedRank 为 NULL 的参赛方(异常数据)排到最后,避免数据库 NULL 先序
        // 占位后又被打乱导致对阵出现空槽
        if (!perCompetitorRound) {
            comps.sort(Comparator.comparing((TCompetitor c) ->
                c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank()));
        }
        // 配置校验通过后,清除旧对阵重新生成(未开赛场景)
        if (hasOldMatches) {
            clearStageMatches(stage.getId());
            log.info("赛段[{}]对阵已存在,未开赛前重新生成:已清除旧对阵", stage.getId());
        }
        // 保留原始种子位置:按 seedRank 落位,跳过场次/缺位留空,避免后续胜者抢占被跳过场次的位置
        long maxSeed = comps.stream().map(TCompetitor::getSeedRank).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0L);
        // 淘汰赛按赛段计划规模(teamCountStart)兜底:人数不足时仍生成完整 bracket,缺位以轮空结算,
        // 与预排(prebracket)及前端对战树预览保持一致;其余赛制不受影响
        long plannedSlots = profile.setup().has(Trait.SLOTS_FROM_TEAM_COUNT) && stage.getTeamCountStart() != null
            ? stage.getTeamCountStart() : 0L;
        int slotCount = (int) Math.max(Math.max(comps.size(), Math.min(maxSeed, 4096L)), Math.min(plannedSlots, 4096L));
        List<Long> seededIds = new ArrayList<>(Collections.nCopies(slotCount, null));
        int nextFree = 0;
        for (TCompetitor c : comps) {
            int idx = c.getSeedRank() != null && c.getSeedRank() > 0 && c.getSeedRank() <= slotCount
                ? (int) (c.getSeedRank() - 1) : -1;
            if (idx < 0) {
                while (nextFree < slotCount && seededIds.get(nextFree) != null) {
                    nextFree++;
                }
                idx = nextFree < slotCount ? nextFree : -1;
            }
            if (idx >= 0) {
                seededIds.set(idx, c.getId());
            }
        }

        // 淘汰赛:配对方式未配置时补本赛段自己的默认值(顺序相邻),与中间态/大屏同一口径
        if (profile.setup().has(Trait.RESOLVES_KNOCKOUT_PAIRING) && rc != null && rc.getKnockout() != null) {
            rc.getKnockout().setPairingMode(PairingModeResolver.resolve(rc.getKnockout().getPairingMode()));
        }
        BracketPlan plan = generatorFactory.generate(mode, seededIds, rc);

        List<MatchPlan> sorted = new ArrayList<>(plan.getMatches());
        sorted.sort(Comparator.comparingInt(MatchPlan::getRound).thenComparingInt(MatchPlan::getMatchIndex));

        // 本赛段名单来源是否已全部结算:决定空座位是"轮空"还是"待定"(见下方落行处)
        boolean sourcesReady = rosterService.isRosterReady(stage.getId());

        // 第一遍:建 TMatch + TMatchRound + TMatchParticipant,记录 (round,index) -> matchId
        Map<String, Long> matchKeyToId = new HashMap<>();
        for (MatchPlan mp : sorted) {
            TMatch m = new TMatch();
            m.setTournamentId(stage.getTournamentId());
            m.setStageId(stage.getId());
            m.setName(mp.getName());
            m.setDisplayRow(mp.getDisplayRow() == null ? null : mp.getDisplayRow().longValue());
            m.setDisplayCol(mp.getDisplayCol() == null ? null : mp.getDisplayCol().longValue());
            m.setDisplayZone(mp.getDisplayZone());
            m.setStatus(StageConstants.MATCH_PENDING);
            m.setMatchMode(matchMode);
            m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
            matchMapper.insert(m);
            matchKeyToId.put(matchKey(mp.getRound(), mp.getMatchIndex()), m.getId());

            // 海选赛/排名赛:每个选手一个轮次,按上场顺序
            if (perCompetitorRound) {
                int seq = 1;
                for (SlotPlan slot : mp.getSlots()) {
                    TMatchRound round = new TMatchRound();
                    round.setTournamentId(stage.getTournamentId());
                    round.setMatchId(m.getId());
                    round.setRoundSequence((long) seq);
                    round.setCompetitorId(slot.getCompetitorId());
                    round.setStatus(StageConstants.MATCH_PENDING);
                    matchRoundMapper.insert(round);
                    seq++;
                }
            } else {
                TMatchRound round = new TMatchRound();
                round.setTournamentId(stage.getTournamentId());
                round.setMatchId(m.getId());
                round.setRoundSequence(1L);
                round.setStatus(StageConstants.MATCH_PENDING);
                matchRoundMapper.insert(round);
            }

            for (SlotPlan slot : mp.getSlots()) {
                // 每个座位都落一行:真人=PLAYER,轮空=BYE,待上游填入=PENDING。
                // 轮空不再"跳过"——否则参赛方数组下标 ≠ 座位下标,前端与下游按 slot 还原位置时会错位
                // (典型:「左轮空、右有人」时把右边的人画到左边)。
                // 空位是"待定"还是"轮空":本赛段名单来源全部结算了,没人来就是真轮空(BYE);
                // 上一赛段还没打完(抢先生成对阵),空位是待定(PENDING)——这人可能马上就来,
                // 一旦当成轮空,场上那 1 个人会被直接判晋级。
                TMatchParticipant p = new TMatchParticipant();
                p.setTournamentId(stage.getTournamentId());
                p.setMatchId(m.getId());
                p.setCompetitorId(slot.getCompetitorId());
                p.setDisplaySlotIndex((long) slot.getSlotIndex());
                p.setSlotKind(slot.getCompetitorId() != null
                    ? StageConstants.SLOT_PLAYER
                    : (slot.isBye() && sourcesReady ? StageConstants.SLOT_BYE : StageConstants.SLOT_PENDING));
                p.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
                participantMapper.insert(p);
            }
        }

        // 第二遍:按下游引用回填 promotion_rule(用真实 matchId)。海选赛跳过,由 completeStage 结算晋级
        if (!perCompetitorRound) {
            for (MatchPlan mp : sorted) {
                // 无胜者去向的场次(如小组赛积分制,按组累计晋级)不写 promotion_rule,
                // 否则 winnerTargetRound 为 null 会在下方 unboxing 时 NPE
                if (!mp.isFinalMatch() && mp.getWinnerTargetRound() == null) {
                    continue;
                }
                PromotionTarget target = new PromotionTarget();
                if (mp.isFinalMatch()) {
                    target.setAction(StageConstants.ACTION_FINAL_ADVANCE);
                } else {
                    target.setAction(StageConstants.ACTION_ADVANCE);
                    target.setTargetMatchId(matchKeyToId.get(matchKey(mp.getWinnerTargetRound(), mp.getWinnerTargetMatchIndex())));
                    target.setTargetSlot(mp.getWinnerTargetSlot());
                }
                Map<String, PromotionTarget> rule = new LinkedHashMap<>();
                rule.put("1", target);
                // 季军赛:半决赛败者路由到败者组场次(rule key "2")
                if (mp.getLoserTargetRound() != null) {
                    PromotionTarget loserTarget = new PromotionTarget();
                    loserTarget.setAction(StageConstants.ACTION_ADVANCE);
                    loserTarget.setTargetMatchId(matchKeyToId.get(matchKey(mp.getLoserTargetRound(), mp.getLoserTargetMatchIndex())));
                    loserTarget.setTargetSlot(mp.getLoserTargetSlot());
                    rule.put("2", loserTarget);
                }
                String json = RuleConfigParser.toJsonPromotionRule(rule);
                TMatch upd = new TMatch();
                upd.setId(matchKeyToId.get(matchKey(mp.getRound(), mp.getMatchIndex())));
                upd.setPromotionRule(json);
                matchMapper.updateById(upd);
            }
        }

        // 海选分圈:生成对阵后自动绑定圈与裁判(优先按 ruleConfig.circleRefereeIds,未配置时圈数=裁判数则 1:1,否则全部绑每圈)
        if (profile.setup().has(Trait.CIRCLE_SPLIT)) {
            auditionCircleSupport.assignCircleReferees(stage);
        }

        log.info("赛段[{}]生成对阵完成:bracketSize={}, 场次数={}", stage.getId(), plan.getBracketSize(), sorted.size());
        tournamentEventNotifier.notify(stage.getTournamentId(), stage.getId(), null, "stage");
    }

    /** 级联清除赛段已生成的全部场次(轮次/参赛明细/打分),用于重新生成对阵 */
    private void clearStageMatches(Long stageId) {
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId).select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (matchIds.isEmpty()) {
            return;
        }
        List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
            .stream().map(TMatchRound::getId).toList();
        if (!roundIds.isEmpty()) {
            roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds));
        }
        participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery().in(TMatchReferee::getMatchId, matchIds));
        matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery().in(TMatchRound::getMatchId, matchIds));
        matchMapper.delete(Wrappers.<TMatch>lambdaQuery().in(TMatch::getId, matchIds));
    }

    /** 持久化海选分圈方式(随机 true / 按号 false),保留 rule_config 中其余自定义字段 */
    private void persistCircleSplitMode(TStage stage, boolean randomSplit) {
        if (StringUtils.isBlank(stage.getRuleConfig())) {
            return;
        }
        try {
            tools.jackson.databind.ObjectMapper mapper = SnowflakeJson.mapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(stage.getRuleConfig(), Map.class);
            raw.put("randomSplit", randomSplit);
            stage.setRuleConfig(mapper.writeValueAsString(raw));
            stageMapper.updateById(stage);
        } catch (Exception e) {
            log.warn("海选赛段[{}]持久化分圈方式失败: {}", stage.getId(), e.getMessage());
        }
    }

    /** 海选计划圈数(ruleConfig.circles),见 {@link AuditionCircleSupport#plannedCircleCount} */
    private int plannedCircleCount(TStage stage) {
        return auditionCircleSupport.plannedCircleCount(stage);
    }

    /** 种子排序口径:把画像里的数据型枚举落成比较器(号码解析留在 service 层,保持 engine 纯净)。 */
    private static Comparator<TCompetitor> seedComparator(SeedOrder order) {
        return switch (order) {
            case BY_NUMBER -> Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber()));
            case BY_SEED -> Comparator.comparing(
                (TCompetitor c) -> c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank());
            case SEED_THEN_NUMBER -> Comparator
                .comparing((TCompetitor c) -> c.getSeedRank() == null ? Long.MAX_VALUE : c.getSeedRank())
                .thenComparingInt(c -> parseCompetitorNumber(c.getNumber()));
        };
    }

    private static String matchKey(int round, int matchIndex) {
        return round + ":" + matchIndex;
    }
}
