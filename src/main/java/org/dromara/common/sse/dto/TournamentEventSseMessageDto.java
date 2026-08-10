package org.dromara.common.sse.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 赛事事件 SSE 推送消息(按 tournamentId 广播,可定向到裁判)
 * 大屏/导播台订阅后收到事件即刷新,裁判订阅只收 refereeIds 命中自己的定向事件
 *
 * @author duane
 */
@Data
public class TournamentEventSseMessageDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标赛事ID */
    private Long tournamentId;

    /** 消息体(JSON 字符串,前端收到后触发刷新) */
    private String message;

    /** 目标裁判ID列表(可选):非空时只推送给该赛事的这些裁判连接,空则广播给赛事全部连接 */
    private List<Long> refereeIds;
}
