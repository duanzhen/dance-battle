package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 中间态名单的"移动"意图:把某一行移到某个座位(或移到待落位区)。
 *
 * <p>前端只表达"谁移到哪",由后端做落位与占位者处理,并把最新名单整份返回——
 * 前端不再自己算座位、也不再整单回传。</p>
 *
 * @author duane
 */
@Data
public class TStageRosterMoveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 要移动的行:中间层行 ID(优先)。与 sourceCompetitorId 二选一 */
    private Long overrideId;

    /** 要移动的人:来源参赛方 ID(前端按人拖动时用) */
    private Long sourceCompetitorId;

    /** 目标座位号;为空(或 toHolding=true)表示移到待落位区 */
    private Long targetSeed;

    /** true = 移到待落位区(没有座位号) */
    private Boolean toHolding;
}
