package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 嘉宾加入请求业务对象。
 *
 * <p>嘉宾可在除海选外的任意赛段、赛段中间态(PENDING/GAMING)加入:
 * 服务端会为其创建参赛单位,若赛段已生成对阵,则按赛制自动挂入未结算场次,
 * 使其可被裁判打分并参与结算/晋级。</p>
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
     * 嘉宾展示名称
     */
    @NotBlank(message = "嘉宾名称不能为空")
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
