package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 导播台/管理端开始场次的可选参数。
 *
 * <p>{@code format} 传 BO1/BO3/BO5 时只作用于本场,开赛时写入 {@code t_match.format},不改赛段默认;
 * 空或省略则按赛段 ruleConfig 的 format 开赛并同样写入本场。</p>
 *
 * @author duane
 */
@Data
public class StartMatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** BO1/BO3/BO5;空或省略表示使用赛段默认 */
    private String format;
}
