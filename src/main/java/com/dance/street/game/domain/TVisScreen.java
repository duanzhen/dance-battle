package com.dance.street.game.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 大屏屏幕配置对象 t_vis_screen。
 *
 * <p>屏幕列表从「每个浏览器标签页各自 history.state」改为服务端持久化,使多个控制端
 * 共用同一份屏幕列表:任一台新增/改名/删除屏幕,其它控制端都能看到;投射状态也落在
 * {@link #currentSceneId} 上,新打开的控制端能直接读到当前投射。</p>
 *
 * @author duane
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_vis_screen")
public class TVisScreen extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
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
