package com.dance.street.game.service;

import com.dance.street.game.domain.bo.MatchRefereeBo;
import com.dance.street.game.domain.vo.MatchRefereeVo;

import java.util.List;

/**
 * 裁判-场次(圈)关联 Service
 *
 * @author duane
 */
public interface ITMatchRefereeService {

    /**
     * 分配某场次(圈)对应的裁判(全量替换)
     */
    void assign(MatchRefereeBo bo);

    /**
     * 查询某赛段各场次(圈)已分配的裁判
     */
    List<MatchRefereeVo> listByStageId(Long stageId);
}
