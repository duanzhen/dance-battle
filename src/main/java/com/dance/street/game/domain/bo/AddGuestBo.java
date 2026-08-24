package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * GUEST 加入请求业务对象。
 *
 * <p>GUEST 可在除海选外的任意赛段、赛段规划/未开始态(DRAFT/PENDING)且未初始化时加入:
 * 服务端仅创建参赛单位进入 GUEST 池,不自动挂入场次;由导播按外部抽签结果设定种子顺序后,
 * initialize → generateMatches 生成对阵,GUEST 与正赛选手同池竞技、胜出即占晋级名额。</p>
 *
 * @author duane
 */
@Data
public class AddGuestBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 加入的赛段ID
     */
    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    /**
     * GUEST 展示名称
     */
    @NotBlank(message = "GUEST 名称不能为空")
    private String name;

    /**
     * 0:个人, 1:队伍(默认个人)
     */
    private Long type;

    /**
     * 选手号(可选,留空自动生成 G+序号)
     */
    private String number;

    /**
     * 关联选手ID(可选,提供后关联参赛成员,头像/选手信息可展示)
     */
    private Long playerId;

}
