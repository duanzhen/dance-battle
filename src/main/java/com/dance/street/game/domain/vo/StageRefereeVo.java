package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 赛段裁判分配视图
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class StageRefereeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long stageId;

    /** 该赛段已分配的裁判ID列表 */
    private List<Long> refereeIds;
}
