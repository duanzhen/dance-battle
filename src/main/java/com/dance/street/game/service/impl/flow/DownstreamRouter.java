package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 淘汰链下游路由的唯一入口:按本场 {@code promotion_rule} 把胜者/败者送到该去的地方。
 *
 * <p>规则 key 约定:{@code "1"}=胜者,{@code "2"}=败者(季军赛)。
 * 动作约定:{@code FINAL_ADVANCE}=标记晋级下一赛段;{@code ADVANCE}=填入下游场次占位。</p>
 *
 * <p>此前这段逻辑写了两份(提交结果、轮空结算)且行为不一致:一份会在下游没有占位行时
 * <strong>补插</strong>一行(生成对阵时空槽不落 participant 行,必须补插),
 * 另一份只做 update,占位缺失时胜者会凭空消失。收敛到本类后统一走"先更新、缺则补插"。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownstreamRouter {

    /** promotion_rule 中胜者/败者的 key */
    public static final String KEY_WINNER = "1";
    public static final String KEY_LOSER = "2";

    private final TMatchParticipantMapper participantMapper;
    private final TCompetitorMapper competitorMapper;
    /** 赛段级结果唯一写入口(晋级/淘汰/名次) */
    private final CompetitorOutcomeWriter outcomeWriter;

    /** 本场胜者(第 1 名)走向:决赛标晋级,否则填下游占位;无规则时什么也不做 */
    public void routeWinner(TMatch match, Long winnerCompetitorId) {
        route(match, KEY_WINNER, winnerCompetitorId);
    }

    /** 本场败者(第 2 名)走向:仅季军赛这类带 {@code "2"} 规则的场次有去向 */
    public void routeLoser(TMatch match, Long loserCompetitorId) {
        route(match, KEY_LOSER, loserCompetitorId);
    }

    /** 淘汰赛败者统一标淘汰(胜者由 {@link #routeWinner} 处理去向) */
    public void markEliminated(Long competitorId) {
        if (competitorId != null) {
            outcomeWriter.writeOutcome(competitorId, OutcomeStatusEnum.ELIMINATED.getCode());
        }
    }

    /**
     * 按 promotion_rule 的指定 key 路由一名参赛方。
     *
     * @param ruleKey {@link #KEY_WINNER} / {@link #KEY_LOSER}
     */
    public void route(TMatch match, String ruleKey, Long competitorId) {
        if (match == null || competitorId == null) {
            return;
        }
        Map<String, PromotionTarget> rule = RuleConfigParser.parsePromotionRule(match.getPromotionRule());
        PromotionTarget target = rule.get(ruleKey);
        if (target == null || target.getAction() == null) {
            return;
        }
        if (StageConstants.ACTION_FINAL_ADVANCE.equals(target.getAction())) {
            markAdvance(match, competitorId);
            return;
        }
        if (StageConstants.ACTION_ADVANCE.equals(target.getAction())
            && target.getTargetMatchId() != null && target.getTargetSlot() != null) {
            fillDownstreamSlot(match, target.getTargetMatchId(),
                target.getTargetSlot().longValue(), competitorId);
        }
    }

    /**
     * 把参赛方填入下游场次的指定槽位:占位行已存在(旧版预建/被重置过)则更新,
     * 不存在则补插——生成对阵时空槽不落 participant 行,只 update 会静默丢人。
     */
    public void fillDownstreamSlot(TMatch sourceMatch, Long targetMatchId, Long targetSlot, Long competitorId) {
        TMatchParticipant upd = new TMatchParticipant();
        upd.setCompetitorId(competitorId);
        int affected = participantMapper.update(upd, Wrappers.<TMatchParticipant>lambdaUpdate()
            .eq(TMatchParticipant::getMatchId, targetMatchId)
            .eq(TMatchParticipant::getDisplaySlotIndex, targetSlot));
        if (affected > 0) {
            return;
        }
        TMatchParticipant np = new TMatchParticipant();
        np.setTenantId(sourceMatch.getTenantId());
        np.setTournamentId(sourceMatch.getTournamentId());
        np.setMatchId(targetMatchId);
        np.setCompetitorId(competitorId);
        np.setDisplaySlotIndex(targetSlot);
        np.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        participantMapper.insert(np);
        log.info("参赛方[{}]补插到下游场次[{}]占位(slot={})", competitorId, targetMatchId, targetSlot);
    }

    /**
     * 标记晋级下一赛段:{@code finalRank = 场次位置(displayRow + 1)}。
     *
     * <p>名次即下一赛段的预排种子依据(见 getPreBracket);场次缺 displayRow 时
     * 退回"当前已晋级人数 + 1",保证名次仍然唯一可用。</p>
     */
    public void markAdvance(TMatch match, Long competitorId) {
        Long rank = match.getDisplayRow() != null ? match.getDisplayRow() + 1 : null;
        if (rank == null) {
            long count = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, match.getStageId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
            rank = count + 1;
        }
        outcomeWriter.writeResult(competitorId, OutcomeStatusEnum.ADVANCE.getCode(), rank);
    }
}
