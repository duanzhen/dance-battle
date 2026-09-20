package com.dance.street.game.service;

import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.vo.StageRefereeVo;

import java.util.List;

/**
 * 裁判-赛段关联 Service
 *
 * @author duane
 */
public interface ITRefereeStageService {

    /**
     * 查询赛段分配的裁判ID列表
     */
    List<Long> getRefereeIdsByStageId(Long stageId);

    /**
     * 批量取多个赛段的裁判ID(场次列表/统计用):一次查完,避免按场次逐次回查。
     *
     * @return stageId -&gt; 裁判ID列表(没有绑定的赛段不出现在 Map 里)
     */
    java.util.Map<Long, List<Long>> getRefereeIdsByStageIds(java.util.Collection<Long> stageIds);

    /**
     * 批量设置赛段裁判（全量替换）
     */
    void assignReferees(StageRefereeBo bo);

    /**
     * 查询裁判负责的赛段ID列表
     */
    List<Long> getStageIdsByRefereeId(Long refereeId);
}
