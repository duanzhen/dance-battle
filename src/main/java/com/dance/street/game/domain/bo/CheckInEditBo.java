package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编辑签到结果请求业务对象
 *
 * <p>用于已签到选手改号/换圈:competitorNumber 为新的目标号码(为空表示不改号),
 * matchId 仅在随机分圈手动换圈时传入(按号分圈由号码自动决定圈位)。</p>
 *
 * @author duane
 * @date 2026-09-03
 */
@Data
public class CheckInEditBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 选手ID */
    @NotNull(message = "选手ID不能为空")
    private Long playerId;

    /** 新的参赛号码(可选:为空表示不改号,仅改名/头像/换圈) */
    private String competitorNumber;

    /** 目标圈场次ID(可选:随机分圈模式手动换圈时传入) */
    private Long matchId;

    /** 选手名称(可选) */
    private String name;

    /** 头像(可选) */
    private String avatar;
}
