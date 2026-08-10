package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量设置赛段裁判
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class StageRefereeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    @NotNull(message = "赛事ID不能为空")
    private Long tournamentId;

    /** 分配给该赛段的裁判ID列表（全量替换） */
    private List<Long> refereeIds;
}
