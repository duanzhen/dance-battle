package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 参赛方落圈请求:海选/排名赛补签到时由调用方指定目标圈。
 *
 * <p>落圈一律由客户端决定,后端不再按号码或名额推导;多圈时两者必须传其一。</p>
 *
 * @author duane
 */
@Data
public class AssignCircleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标圈场次ID(已拿到圈场次时使用) */
    private Long matchId;

    /** 目标圈序号(1 起):尚未拿到圈场次ID 时用圈序号定位 */
    private Integer zoneIndex;
}
