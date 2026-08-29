package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 裁判-场次(圈)分配视图
 *
 * @author duane
 */
@Data
public class MatchRefereeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场次ID(海选一圈一场) */
    private Long matchId;

    /** 裁判ID */
    private Long refereeId;

    /** 裁判姓名 */
    private String refereeName;
}
