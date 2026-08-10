package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图层批量重排序请求:按 widgetIds 顺序(从上到下)重新分配 zIndex。
 * 
 * @author duane
 * @date 2026-08-09
 */
@Data
public class ReorderBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "场景ID不能为空")
    private Long sceneId;

    @NotEmpty(message = "控件顺序不能为空")
    private List<Long> widgetIds;
}
