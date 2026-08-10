package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 比赛结果提交(裁判台实时 / 管理端批量共用)。
 *
 * <p>一套结构表达三种打分模式:</p>
 * <ul>
 *   <li>{@code outcomes}:直接判胜负平(STANDARD 模式),competitorId -> WIN/LOSS/DRAW</li>
 *   <li>{@code scores}:明细分(VOTING / RANKING 模式)</li>
 * </ul>
 *
 * @author duane
 * @date 2026-08-08
 */
@Data
public class SubmitResultBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "场次ID不能为空")
    private Long matchId;

    /** 直接判定的胜负平(STANDARD):competitorId -> WIN/LOSS/DRAW */
    private Map<Long, String> outcomes;

    /** 明细分(VOTING/RANKING) */
    private List<ScoreEntryBo> scores;

    /** 提交裁判ID(裁判台提交时携带,可用于权限校验) */
    private Long refereeId;

    /** 提交后若该赛段所有场次已结算,是否自动 complete 赛段(默认 true) */
    private Boolean finalizeStageIfComplete;
}
