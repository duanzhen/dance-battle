package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 海选「晋级人数」展示口径:裁判端与 MC 导播台都要显示
 * 「本场(本圈/加赛)晋级几人、本赛段共晋级几人」。
 *
 * <p>数值必须与结算完全一致,否则现场看到的晋级线和实际结果对不上,因此这里复用
 * {@link AuditionStageSettler} 结算时的同一套口径:</p>
 * <ul>
 *   <li>本圈名额来自 {@link StageFlowSupport#circleQuotaContext}(每圈可单独配置);</li>
 *   <li>加赛(二海/三海…)只争本圈<b>剩余</b>名额 = 本圈名额 - 本圈已晋级人数;</li>
 *   <li>已晋级人数按人去重——同一名晋级者在原圈与各级加赛都有参赛行,按行数会重复计数。</li>
 * </ul>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class AuditionAdvanceInfoSupport {

    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;

    /** 本赛段计划晋级总人数(ruleConfig.advanceCount) */
    public int stageAdvanceCount(TStage stage) {
        return stage == null ? 0 : StageFlowSupport.readStageAdvanceCount(stage);
    }

    /** 单场:本场(本圈/加赛)晋级人数 */
    public int matchAdvanceCount(TStage stage, TMatch match) {
        if (stage == null || match == null) {
            return 0;
        }
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId()));
        return matchAdvanceCounts(stage, matches).getOrDefault(match.getId(), 0);
    }

    /**
     * 批量:场次ID → 本场晋级人数(一次查库,供场次列表使用)。
     *
     * <p>只对海选给值;名额配置非法(如总晋级数无法按圈均分)时返回空表——展示层不该因为
     * 配置问题把页面打挂,真正的报错由生成/结算抛出。</p>
     */
    public Map<Long, Integer> matchAdvanceCounts(TStage stage, List<TMatch> matches) {
        Map<Long, Integer> out = new HashMap<>();
        if (stage == null || matches == null || matches.isEmpty()
            || !StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            return out;
        }
        Map<String, StageFlowSupport.CircleQuota> zoneCtx;
        try {
            zoneCtx = StageFlowSupport.circleQuotaContext(stage, matches, "海选");
        } catch (RuntimeException e) {
            return out;
        }
        Map<String, Integer> advanced = advancedByZone(matches);
        for (TMatch m : matches) {
            StageFlowSupport.CircleQuota qb = zoneCtx.get(m.getDisplayZone());
            if (qb == null) {
                continue;
            }
            // 正式圈 = 本圈名额;加赛 = 本圈剩余名额(与结算 settleAuditionMatch 同一口径)
            int count = SettlementSupport.isTiebreaker(m)
                ? Math.max(0, qb.quota() - advanced.getOrDefault(m.getDisplayZone(), 0))
                : qb.quota();
            out.put(m.getId(), count);
        }
        return out;
    }

    /** 加赛轮次名:二海/三海…;非加赛返回 null(供前端拼「二海 争 2 个名额」) */
    public String tiebreakerRoundName(TMatch match) {
        if (!SettlementSupport.isTiebreaker(match)) {
            return null;
        }
        String name = match.getName() == null ? "" : match.getName();
        int depth = 0;
        for (int i = name.indexOf("加赛"); i >= 0; i = name.indexOf("加赛", i + 2)) {
            depth++;
        }
        return switch (Math.max(1, depth)) {
            case 1 -> "二海";
            case 2 -> "三海";
            case 3 -> "四海";
            case 4 -> "五海";
            default -> "加赛" + Math.max(1, depth);
        };
    }

    /** 各圈已晋级人数(按人去重):与 AuditionStageSettler#countAuditionAdvancedByZone 同一口径 */
    private Map<String, Integer> advancedByZone(List<TMatch> matches) {
        Map<Long, String> matchZone = new HashMap<>();
        for (TMatch m : matches) {
            matchZone.put(m.getId(), m.getDisplayZone());
        }
        Map<Long, String> advancerZone = new HashMap<>();
        participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchZone.keySet())
                .eq(TMatchParticipant::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()))
            .forEach(p -> {
                if (p.getCompetitorId() != null) {
                    advancerZone.putIfAbsent(p.getCompetitorId(),
                        matchZone.getOrDefault(p.getMatchId(), ""));
                }
            });
        Map<String, Integer> out = new HashMap<>();
        for (String zone : advancerZone.values()) {
            out.merge(zone, 1, Integer::sum);
        }
        return out;
    }
}
