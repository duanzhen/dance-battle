package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchRoundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「当前生效轮」的唯一口径。
 *
 * <p>BO3/BO5 开赛时会预建全部轮次:第 1 局 GAMING,其余 PENDING。当前轮取序号最小的
 * 未结算轮;场次已结束则取最后一局已结算轮。这样预建轮次不会被误当成当前判罚轮,
 * 同时保留 BO1 平局加赛等超出预建数量的极端场景。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class MatchRoundLocator {

    private final TMatchRoundMapper matchRoundMapper;

    /** 取场次的当前生效轮;一个轮次都没有时补建第 1 轮并返回。 */
    public TMatchRound current(TMatch match) {
        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())
            .orderByAsc(TMatchRound::getRoundSequence));
        TMatchRound current = pickCurrent(rounds, match.getStatus());
        if (current != null) {
            return current;
        }
        TMatchRound round = new TMatchRound();
        round.setTournamentId(match.getTournamentId());
        round.setMatchId(match.getId());
        round.setTenantId(match.getTenantId());
        round.setRoundSequence(1L);
        round.setStatus(StageConstants.MATCH_GAMING);
        matchRoundMapper.insert(round);
        return round;
    }

    /**
     * 从按序号升序排列的轮次中选当前轮。
     *
     * <p>进行中:优先取唯一 GAMING 轮;没有 GAMING 时取第一局未结算轮;
     * 已结束:最后一局已结算轮。手动切换后目标轮会置 GAMING,因此会稳定成为当前轮。
     * 这样预建的后续轮次只作为赛程占位,不会抢走当前判罚轮。</p>
     */
    public static TMatchRound pickCurrent(List<TMatchRound> rounds, String matchStatus) {
        if (rounds == null || rounds.isEmpty()) {
            return null;
        }
        if (StageConstants.MATCH_SETTLED.equals(matchStatus)) {
            TMatchRound lastSettled = null;
            for (TMatchRound round : rounds) {
                if (StageConstants.MATCH_SETTLED.equals(round.getStatus())) {
                    lastSettled = round;
                }
            }
            return lastSettled != null ? lastSettled : rounds.get(rounds.size() - 1);
        }
        for (TMatchRound round : rounds) {
            if (StageConstants.MATCH_GAMING.equals(round.getStatus())) {
                return round;
            }
        }
        for (TMatchRound round : rounds) {
            if (!StageConstants.MATCH_SETTLED.equals(round.getStatus())) {
                return round;
            }
        }
        return rounds.get(rounds.size() - 1);
    }
}
