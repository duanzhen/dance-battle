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
     * 批量设置赛段裁判（全量替换）
     */
    void assignReferees(StageRefereeBo bo);

    /**
     * 查询裁判负责的赛段ID列表
     */
    List<Long> getStageIdsByRefereeId(Long refereeId);
}
