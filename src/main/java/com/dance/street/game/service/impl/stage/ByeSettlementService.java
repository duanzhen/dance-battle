package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.impl.flow.DownstreamRouter;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轮空场次结算。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者。虽然只有百来行,
 * 但它承载了两类真实事故的修复:一是"半决赛双方都轮空"时季军赛永远等不到人,
 * 二是"上一赛段还没打完就把空位当轮空"导致人没打就晋级。因此逻辑与注释一并保留。</p>
 *
 * <p>被 {@code TMatchResultServiceImpl} 的「开始场次」直接调用(轮空场点开始即结算)。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ByeSettlementService {

    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final ITStageRosterService rosterService;
    /** 结算共享内核(场次置结算) */
    private final SettlementSupport settlementSupport;
    /** 淘汰链下游路由唯一入口(轮空胜者与正常胜者共用同一套去向逻辑) */
    private final DownstreamRouter downstreamRouter;

    /**
     * 轮空场次自动结算:单边轮空(1 名真人)直接判胜,按淘汰赛规则填下游占位或标记晋级;
     * 双边轮空(两个空位)无胜者,仅置为已结算。返回本次结算的场次数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int settleByeMatches(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || !StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            return 0;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .in(TMatch::getStatus, StageConstants.MATCH_PENDING, StageConstants.MATCH_GAMING));
        int settled = 0;
        for (TMatch m : matches) {
            if (settleByeMatch(m)) {
                settled++;
            }
        }
        if (settled > 0) {
            log.info("赛段[{}]轮空场次自动结算 {} 场", stageId, settled);
        }
        return settled;
    }

    /** 结算单个轮空场次(按场次ID);非淘汰赛或非轮空返回 false。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleByeMatch(Long matchId) {
        if (matchId == null) {
            return false;
        }
        TMatch match = matchMapper.selectById(matchId);
        if (match == null) {
            return false;
        }
        TStage stage = stageMapper.selectById(match.getStageId());
        if (stage == null || !StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())) {
            return false;
        }
        return settleByeMatch(match);
    }

    /** 结算单个轮空场次:单边轮空(1 名真人)判胜并填下游/标晋级;双边轮空仅置已结算。非轮空返回 false。 */
    private boolean settleByeMatch(TMatch m) {
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, m.getId())
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        List<TMatchParticipant> real = parts.stream()
            .filter(p -> p.getCompetitorId() != null)
            .toList();
        if (real.size() >= 2) {
            return false; // 正常对决,不处理
        }
        // 空场次能否按轮空结算,取决于"还会不会有人补进来",两处都要看:
        //  · 本赛段内:所有会向本场送人的上游场次是否都已结算(胜/败者该来的都来了、
        //    该空的就永远空);此前用 displayCol > 1 粗判,导致"半决赛双方都是轮空"时
        //    季军赛永远等不到人:既开不了也结算不了,整个赛段卡死在"仍有 1 场未结算"。
        //  · 跨赛段:本赛段的名单来源是否已全部结算 —— 上一赛段没打完时,空位是"待定",
        //    此时把场上那 1 个人按轮空直接判晋级,就是现场"轮空/待定混在一起"的事故
        //    (人还没打就晋级了,后面的判罚全乱)。
        if (hasUnsettledUpstream(m) || !rosterService.isRosterReady(m.getStageId())) {
            return false;
        }
        if (real.size() == 1) {
            TMatchParticipant winner = real.get(0);
            TMatchParticipant upd = new TMatchParticipant();
            upd.setOutcomeStatus(MatchOutcomeEnum.WIN.getCode());
            upd.setRankInMatch(1L);
            participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
                .eq(TMatchParticipant::getMatchId, m.getId())
                .eq(TMatchParticipant::getCompetitorId, winner.getCompetitorId()));
            resolveKnockoutByeWinner(m, winner.getCompetitorId());
            // 轮空判胜同样是"有人晋级了":立刻写进下一赛段中间态的对应座位。
            // 只走裁判判罚那条路的话,这里晋级的选手不会出现在下一段(现场表现:"点了开始,人却没晋级")。
            rosterService.syncPreAdvance(m.getStageId(), List.of(winner.getCompetitorId()));
        }
        settlementSupport.markMatchSettled(m);
        return true;
    }

    /**
     * 本赛段内是否还有会向 {@code match} 填入参赛方的未结算场次(按 promotion_rule 反查)。
     *
     * <p>跨赛段的晋级走名单装配,开赛时参赛行已物化完毕(名单来源全部 SETTLED 才能开赛),
     * 因此只在本赛段内反查即可。</p>
     */
    private boolean hasUnsettledUpstream(TMatch match) {
        List<TMatch> sameStage = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, match.getStageId())
            .select(TMatch::getId, TMatch::getStatus, TMatch::getPromotionRule));
        for (TMatch m : sameStage) {
            if (Objects.equals(m.getId(), match.getId())
                || StageConstants.MATCH_SETTLED.equals(m.getStatus())) {
                continue;
            }
            Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(m.getPromotionRule());
            for (PromotionTarget target : rule.values()) {
                if (target != null && Objects.equals(target.getTargetMatchId(), match.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 轮空胜者去向:填下游场次占位;finalMatch 则标记晋级下一赛段 */
    private void resolveKnockoutByeWinner(TMatch match, Long winnerCompetitorId) {
        // 与正常结算共用同一套去向逻辑(决赛标晋级 / 填下游占位并补插缺失占位行)
        downstreamRouter.routeWinner(match, winnerCompetitorId);
    }
}
