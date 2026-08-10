package org.dromara.common.sse.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 赛事SSE消息的dto
 *
 * @author Lion Li
 */
@Data
public class TournamentSseMessageDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 需要推送到的屏幕ID列表
     */
    private List<String> screenIds;

    /**
     * 需要推送到的终端ID列表（与screenIds配合使用）
     * 如果为空，则向指定屏幕的所有终端发送
     * 如果有值，则向指定屏幕的指定终端发送
     */
    private List<String> terminalIds;

    /**
     * 需要发送的消息
     */
    private String message;
}
