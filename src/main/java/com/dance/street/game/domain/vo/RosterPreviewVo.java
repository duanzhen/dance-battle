package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 名单实时预览(规则 + 覆盖合并,不落库)。
 *
 * @author duane
 */
@Data
public class RosterPreviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** = 赛段 id */
    private Long stageId;

    private Long targetStageId;

    /** 全部内部来源已结算(就绪度纯函数) */
    private Boolean ready;

    /** 名单是否已物化(CONFIRMED) */
    private Boolean applied;

    /** 名单是否显式跳过 */
    private Boolean skipped;

    /** 目标赛段容量(0=不限) */
    private Integer capacity;

    /** 装配顺序列表(含种子位) */
    private List<RosterPreviewItemVo> items = new ArrayList<>();

    /** 可读提示(如"覆盖与规则冲突已按覆盖优先") */
    private List<String> warnings = new ArrayList<>();
}
