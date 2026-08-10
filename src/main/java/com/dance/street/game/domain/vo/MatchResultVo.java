package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 比赛结果提交后的返回(含算分明细与场次最终状态)。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class MatchResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long matchId;

    /** 场次最终状态(PENDING/GAMING/SETTLED) */
    private String status;

    private List<ParticipantResultVo> participants;
}
