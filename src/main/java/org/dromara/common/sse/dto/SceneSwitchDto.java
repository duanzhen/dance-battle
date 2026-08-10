package org.dromara.common.sse.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 场景切换通知 DTO
 *
 * @author Lion Li
 */
@Data
public class SceneSwitchDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 赛事 ID
     */
    private String tournamentId;

    /**
     * 屏幕 ID
     */
    private String screenId;

    /**
     * 场景 ID
     */
    private String sceneId;
}
