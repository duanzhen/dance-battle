package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 导播台「评分详情」组件选择器条目:组件身份 + 所在场景/屏幕 + 当前推送目标。
 *
 * @author duane
 */
@Data
public class ScoreDetailWidgetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 组件 ID(即推送目标键) */
    private Long id;

    /** 组件名称 */
    private String name;

    /** 所在场景 ID */
    private Long sceneId;

    /** 所在场景名 */
    private String sceneName;

    /** 当前正在投射该场景的屏幕名(可能为空,或有多块屏时用顿号拼接) */
    private String screenName;

    /** 当前已推送的场次 ID;未推送为 null */
    private Long matchId;

    /** 当前已推送的选手 ID;未推送为 null */
    private Long competitorId;
}
