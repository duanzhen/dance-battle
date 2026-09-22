package com.dance.street.game.domain.vo;

import com.dance.street.game.engine.common.StageConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 完成赛段的结果。
 *
 * <p>「完成赛段」有两种正常结局:结算完成、赛段结束;或结算产生了后续工作
 * (海选同分加赛、仍有场次未结算),赛段保持进行中。此前后者在部分赛制里
 * 返回 GAMING、在另一些赛制里直接抛异常,调用方必须知道是哪种赛制才能正确提示;
 * 现在统一用本对象表达,前端只看 {@code completed} 与 {@code message}。</p>
 *
 * @author duane
 */
@Data
public class StageCompleteVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 结算后的赛段状态:SETTLED=已结束;GAMING=仍有后续工作 */
    private String status;

    /** 是否已结束 */
    private Boolean completed;

    /** 未结束时的人话原因(可直接展示给导播),已结束时为 null */
    private String message;

    /**
     * 未结束是否因为产生了同分加赛(二海/三海…)。
     *
     * <p>首次点「完成赛段」会当场生成加赛场次、再次点则是加赛还没判完,
     * 两种情况前端都要弹「需要加赛」提示,而不是当成"漏判了场次"。</p>
     */
    private Boolean tiebreaker;

    /** 赛段已结束 */
    public static StageCompleteVo settled() {
        StageCompleteVo vo = new StageCompleteVo();
        vo.setStatus(StageConstants.STAGE_SETTLED);
        vo.setCompleted(true);
        vo.setTiebreaker(false);
        return vo;
    }

    /** 结算产生了后续工作,赛段保持进行中 */
    public static StageCompleteVo pending(String message) {
        StageCompleteVo vo = new StageCompleteVo();
        vo.setStatus(StageConstants.STAGE_GAMING);
        vo.setCompleted(false);
        vo.setMessage(message);
        vo.setTiebreaker(false);
        return vo;
    }

    /** 结算产生了同分加赛(二海/三海…),赛段保持进行中 */
    public static StageCompleteVo pendingTiebreaker(String message) {
        StageCompleteVo vo = new StageCompleteVo();
        vo.setStatus(StageConstants.STAGE_GAMING);
        vo.setCompleted(false);
        vo.setMessage(message);
        vo.setTiebreaker(true);
        return vo;
    }
}
