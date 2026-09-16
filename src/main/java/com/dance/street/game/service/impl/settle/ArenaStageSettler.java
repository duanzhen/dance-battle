package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.service.impl.CompetitorOutcomeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 擂台赛结算:把轮转队列首位落库为冠军(ADVANCE/finalRank=1),其余按胜场积分
 * 排 2..n 并标记淘汰,使擂台赛结果可被查询、可接续下一赛段晋级。
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaStageSettler implements StageSettler {

    private final TMatchMapper matchMapper;
    private final CompetitorOutcomeWriter outcomeWriter;
    private final ArenaQueueSupport arenaQueueSupport;

    @Override
    public String stageMode() {
        return StageModeEnum.ARENA.getCode();
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        long gaming = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING));
        if (gaming > 0) {
            return StageSettleOutcome.pending("仍有 " + gaming + " 场对决进行中,请先完成或重启后再结束赛段");
        }
        // 「还不能结束」一律用 pending 表达(与"场次未结算"同类),只有配置/数据本身
        // 不合法才抛异常。以下两条都是"现场再做点事就能满足"的条件,不该让导播看到报错。
        List<Long> queue = arenaQueueSupport.computeArenaQueue(stage.getId());
        if (queue.isEmpty()) {
            return StageSettleOutcome.pending("擂台赛没有参赛者,无法完成赛段(请先让选手签到)");
        }
        long battles = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .eq(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (battles == 0) {
            return StageSettleOutcome.pending("擂台赛尚未进行任何对决,无法完成赛段(请先创建并完成至少一场对决)");
        }
        settleArenaStage(stage, queue, battles);
        return StageSettleOutcome.completed();
    }

    /**
     * 落库擂台赛最终名次(前置守卫已在 {@link #settle} 中通过:
     * 无进行中对决、有参赛者、至少完成一场对决)。
     */
    private void settleArenaStage(TStage stage, List<Long> queue, long battles) {
        Map<Long, Integer> points = arenaQueueSupport.arenaPoints(stage.getId());
        Long championId = queue.get(0);
        List<Long> rest = new ArrayList<>(queue.subList(1, queue.size()));
        // 亚军及以后按胜场积分降序;积分相同按参赛方 ID 稳定排序
        rest.sort((a, b) -> {
            int cmp = Integer.compare(points.getOrDefault(b, 0), points.getOrDefault(a, 0));
            return cmp != 0 ? cmp : Long.compare(a, b);
        });
        outcomeWriter.writeResult(championId, OutcomeStatusEnum.ADVANCE.getCode(), 1L);
        long rank = 2L;
        for (Long cid : rest) {
            outcomeWriter.writeResult(cid, OutcomeStatusEnum.ELIMINATED.getCode(), rank++);
        }
        log.info("擂台赛[{}]完成:冠军[{}]({}胜),共{}场对决,{}名参赛者落库排名", stage.getId(),
            championId, points.getOrDefault(championId, 0), battles, queue.size());
    }
}
