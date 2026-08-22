package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 插入嘉宾赛段请求。
 *
 * <p>在指定赛段 A 与其下一赛段 B 之间插入一个淘汰赛赛段 S(仅当 B 尚干净、未接收参赛方时允许),
 * S 用于承载中途加入的嘉宾/外卡:先插赛段、再加入嘉宾,按外部抽签结果排定种子顺序后正常比赛,
 * 胜者按配置的晋级名额进入原下一赛段 B(嘉宾胜出即占晋级名额)。</p>
 *
 * @author duane
 */
@Data
public class InsertStageBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 在哪一赛段之后插入(A)
     */
    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    /**
     * 新赛段名称(如"8进4·嘉宾赛")
     */
    @NotBlank(message = "新赛段名称不能为空")
    private String name;

    /**
     * 新赛段晋级名额(可选,默认取原下一赛段起始人数)
     */
    private Long advanceCount;
}
