package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 签到请求业务对象
 *
 * @author duane
 * @date 2026-02-08
 */
@Data
public class CheckInBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 选手ID
     */
    @NotNull(message = "选手ID不能为空")
    private Long playerId;

    /**
     * 签到类型: CREATE-新建参赛单位, JOIN-加入已有参赛单位
     */
    @NotBlank(message = "签到类型不能为空")
    private String checkInType;

    /**
     * 参赛单位编号（新建时必填）
     */
    private String competitorNumber;

    /**
     * 参赛单位ID（加入时必填）
     */
    private Long competitorId;

    /**
     * 选手名称（可选，签到时可修改）
     */
    private String name;

    /**
     * 头像（可选，签到时可修改）
     */
    private String avatar;

    /**
     * 目标圈场次ID（可选）：海选/排名赛分圈且已生成对阵时，指定新选手挂入的圈；
     * 不传则由系统按各圈剩余名额自动择优。
     */
    private Long matchId;

    /**
     * 目标圈序号（可选，1 起）：海选分圈尚未生成圈场次时，由抽号页选中的计划圈指定；
     * 后端先按配置补建 ZONE-1..n 圈场次，再把新选手挂入该圈。
     */
    private Integer zoneIndex;

}
