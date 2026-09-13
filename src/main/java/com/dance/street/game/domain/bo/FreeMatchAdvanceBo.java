package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 自由对抗:导播台手动选择晋级者(任意人数,顺序即下一赛段种子顺序)。
 *
 * @author duane
 */
@Data
public class FreeMatchAdvanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 晋级选手(参赛方ID);未传入的其余选手标记为淘汰 */
    private List<Long> competitorIds;
}
