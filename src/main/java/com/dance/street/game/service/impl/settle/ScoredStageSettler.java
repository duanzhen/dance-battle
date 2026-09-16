package com.dance.street.game.service.impl.settle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.service.ITScoredMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 多裁判累计打分赛制的结算:淘汰赛与自由对抗。
 *
 * <p>两者的结束条件相同——所有场次都已结算,差别只在"谁决定胜负"
 * (淘汰赛看对局结果,自由对抗由导播手动指定晋级者),而这一步在
 * 完成赛段之前就已落库。因此共用同一个策略,不做无意义拆分。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class ScoredStageSettler implements StageSettler {

    private final ITScoredMatchService scoredMatchService;
    private final TMatchMapper matchMapper;

    @Override
    public String stageMode() {
        return StageModeEnum.KNOCKOUT.getCode();
    }

    @Override
    public boolean supports(String mode) {
        return StageModeEnum.KNOCKOUT.getCode().equals(mode)
            || StageModeEnum.FREE_MATCH.getCode().equals(mode);
    }

    @Override
    public StageSettleOutcome settle(TStage stage) {
        scoredMatchService.settleScoredMatches(stage.getId());
        long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
        if (unfinished > 0) {
            return StageSettleOutcome.pending("赛段仍有 " + unfinished + " 场未结算,完成全部判罚后才能结束赛段");
        }
        return StageSettleOutcome.completed();
    }
}
