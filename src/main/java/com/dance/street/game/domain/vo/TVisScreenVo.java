package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TVisScreen;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 大屏屏幕配置视图对象 t_vis_screen
 *
 * @author duane
 */
@Data
@AutoMapper(target = TVisScreen.class)
public class TVisScreenVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属赛事
     */
    private Long tournamentId;

    /**
     * 屏幕名
     */
    private String name;

    /**
     * 排序
     */
    private Long sortOrder;

    /**
     * 当前投射的场景ID(未投射为 null)
     */
    private Long currentSceneId;

    /**
     * 备注
     */
    private String remark;
}
