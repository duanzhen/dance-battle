package com.dance.street.game.service.impl.flow;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.mapper.TCompetitorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 赛段级参赛结果的唯一写入口(t_competitor.outcome_status / final_rank)。
 *
 * <p>各赛制结算(海选 / 淘汰 / 小组 / 擂台 / 排名 / 自由对抗)与轮空晋级
 * 都要把"谁晋级、谁淘汰、第几名"落库。此前每个结算分支各写一份
 * {@code new TCompetitor() + updateById},字段口径容易分叉;统一走本类后,
 * 结果写入只有一处,新增赛制只需复用。</p>
 *
 * <p>注意 MyBatis-Plus 默认跳过 null 字段,因此 {@code finalRank} 传 null 表示
 * "不改动已落库的名次",不会把名次清空(与既有行为一致)。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class CompetitorOutcomeWriter {

    private final TCompetitorMapper competitorMapper;

    /**
     * 写参赛方的赛段级结果与最终名次。
     *
     * @param competitorId 参赛方ID
     * @param outcome      结果(ADVANCE/ELIMINATED/PENDING/WITHDRAWN)
     * @param finalRank    最终名次;null 表示不改动已落库的名次
     */
    public void writeResult(Long competitorId, String outcome, Long finalRank) {
        TCompetitor upd = new TCompetitor();
        upd.setId(competitorId);
        upd.setOutcomeStatus(outcome);
        upd.setFinalRank(finalRank);
        competitorMapper.updateById(upd);
    }

    /**
     * 只改结果、不动名次(如淘汰赛中先标 ELIMINATED,名次稍后统一排)。
     */
    public void writeOutcome(Long competitorId, String outcome) {
        writeResult(competitorId, outcome, null);
    }
}
