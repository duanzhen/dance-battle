package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 按外部抽签结果批量设定赛段参赛方顺序请求。
 *
 * <p>按列表顺序把参赛方 seedRank 写为 1..n,后续 initialize 按该顺序落位、
 * generateMatches 按 SEQUENTIAL/SEED 配对生成对阵。仅允许在赛段尚未 initialize(名单未锁定)时执行。</p>
 *
 * @author duane
 */
@Data
public class SeedOrderBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 赛段ID
     */
    private Long stageId;

    /**
     * 按抽签结果排列的参赛方ID(顺序即种子顺序)
     */
    @NotEmpty(message = "参赛方顺序不能为空")
    private List<Long> competitorIds;
}
