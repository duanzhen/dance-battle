package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 中间态名单的"移出"意图:把谁从名单里拿掉(可选把后面的人整体顶上一位)。
 *
 * <p>与"移动"一样,前端只表达意图,后端改库并整份返回最新名单。</p>
 *
 * @author duane
 */
@Data
public class TStageRosterRemoveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 要移出的中间层行 ID */
    private List<Long> ids;

    /** 要移出的来源参赛方 ID(按人移出时用) */
    private List<Long> sourceCompetitorIds;

    /** true = 后面的人整体顶上一位(座位号 -1);false = 原地留空位 */
    private Boolean fillGap;
}
