package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 裁判-场次(圈)分配请求
 *
 * @author duane
 */
@Data
public class MatchRefereeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "场次ID不能为空")
    private Long matchId;

    @NotNull(message = "赛事ID不能为空")
    private Long tournamentId;

    @NotEmpty(message = "裁判ID列表不能为空")
    private List<Long> refereeIds;
}
