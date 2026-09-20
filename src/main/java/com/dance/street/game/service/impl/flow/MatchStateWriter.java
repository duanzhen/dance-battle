package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 场次状态推进的唯一入口:写 t_match.status 的同时把它所有轮次置成同一状态。
 *
 * <p>「场次状态」和「轮次状态」是一套东西:裁判端按轮次判罚、结算看场次,
 * 两者不同步时会出现「场次已结算、轮次还在进行中」这类悬挂状态。此前这个成对
 * 写入散落在 6 处以上(提交结果/开始场次/取消开始/重置/轮空结算/多裁判结算),
 * 其中多裁判结算那条路径<strong>漏写了轮次</strong>——正是本类要消灭的那类分叉。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class MatchStateWriter {

    private final TMatchMapper matchMapper;
    private final TMatchRoundMapper matchRoundMapper;

    /** 场次与其全部轮次同步置状态 */
    public void setStatus(Long matchId, String status) {
        TMatch upd = new TMatch();
        upd.setId(matchId);
        upd.setStatus(status);
        matchMapper.updateById(upd);
        setRoundsStatus(matchId, status);
    }

    /** 同上,入参为场次实体 */
    public void setStatus(TMatch match, String status) {
        if (match != null && match.getId() != null) {
            setStatus(match.getId(), status);
        }
    }

    /**
     * 只改轮次状态。
     *
     * <p>用于「场次保持进行中但某一轮结束」的场景(如淘汰赛平局后开新一轮:
     * 旧轮结算、新轮开始),这类场景不能用 {@link #setStatus(Long, String)} 一把梭。</p>
     */
    public void setRoundsStatus(Long matchId, String status) {
        if (matchId == null) {
            return;
        }
        TMatchRound upd = new TMatchRound();
        upd.setStatus(status);
        matchRoundMapper.update(upd, Wrappers.<TMatchRound>lambdaUpdate()
            .eq(TMatchRound::getMatchId, matchId));
    }
}
