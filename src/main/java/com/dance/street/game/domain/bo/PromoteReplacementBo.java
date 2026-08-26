package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 海选弃权/顶替请求。
 *
 * <p>海选结算后、确认晋级前,若晋级者弃权:可只标记弃权(不顶替,淘汰赛对手轮空晋级),
 * 也可指定名次靠下的淘汰者顶替上来(占用弃权者的种子位)。</p>
 */
@Data
public class PromoteReplacementBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 弃权者(源赛段晋级者/已标记弃权者),可为空(仅执行顶替时) */
    private Long withdrawnCompetitorId;

    /** 顶替者(源赛段淘汰者,ELIMINATED),可为空(仅标记弃权、不顶替) */
    private Long replacementCompetitorId;
}
