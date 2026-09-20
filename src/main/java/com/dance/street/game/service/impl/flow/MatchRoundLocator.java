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
 * 「当前生效轮」的唯一口径:一个场次里轮次序号最大的那一轮。
 *
 * <p>多轮场次(淘汰赛平局新增的加赛轮、后续 BO 多局制)中,裁判此刻判的就是最新一轮。
 * 这个选择此前在两个服务里各写了一份:提交结果侧取最新一轮,多裁判累计侧取首轮,
 * 同一场次的"写入轮"与"回显轮"因此会分叉(裁判提交的分进了第 1 轮,回显按最后一轮查,
 * 表现为「提交了却看不到分」)。收敛到本类后只有一个答案。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class MatchRoundLocator {

    private final TMatchRoundMapper matchRoundMapper;

    /**
     * 取场次的当前生效轮(序号最大者);一个轮次都没有时补建第 1 轮并返回。
     */
    public TMatchRound current(TMatch match) {
        List<TMatchRound> rounds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, match.getId())
            .orderByAsc(TMatchRound::getRoundSequence));
        if (!rounds.isEmpty()) {
            return rounds.get(rounds.size() - 1);
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
}
