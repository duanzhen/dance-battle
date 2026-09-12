package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 名单手工顺序:逐项落种子位。
 *
 * <p>中间态两列拖动保存时使用:列表项要么引用源赛段行(sourceCompetitorId),
 * 要么引用一条人工调整(overrideId,如外卡/补入)。</p>
 *
 * <p>每项可显式带 seedRank(拖拽/移出留空位时用,允许出现空位);
 * 未带 seedRank 的按数组顺序补齐到最小空闲位(兼容旧调用)。</p>
 *
 * @author duane
 */
@Data
public class TStageRosterOrderBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<Item> items;

    @Data
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 规则带入的源赛段行 */
        private Long sourceCompetitorId;

        /** 人工调整行(ADD_GUEST/ADD_SOURCE)的调整 ID */
        private Long overrideId;

        /** 目标种子位(第几位);空 = 按数组顺序自动补最小空闲位 */
        private Long seedRank;
    }
}
