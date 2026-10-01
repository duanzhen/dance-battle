package com.dance.street.game.engine.common;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.enums.StageModeEnum;

import java.util.List;

/**
 * 淘汰赛首轮配对方式(配对口径的唯一事实源)。
 *
 * <p>生成对阵、中间态预排、大屏展示必须取同一个结果,否则会出现
 * "配置写了种子交叉,中间态却按相邻配对"这类不一致。</p>
 *
 * @author duane
 */
public final class PairingModeResolver {

    /** 标准种子摆位(头尾交叉:1-16、8-9…) */
    public static final String SEED = "SEED";
    /** 相邻配对(1-2、3-4…) */
    public static final String SEQUENTIAL = "SEQUENTIAL";

    private PairingModeResolver() {
    }

    /**
     * 解析首轮配对方式。
     *
     * <p><b>只看本赛段自己的配置</b>:配了就用配的,没配就按顺序相邻(SEQUENTIAL)。
     * 不再根据"上一赛段是不是海选/排名"或"名单来源里有没有海选"去推断——
     * 头尾交叉与否是赛段配置的显式选择,不依赖链,也不依赖来源。</p>
     *
     * @param configured ruleConfig.knockout.pairingMode(可为空)
     */
    public static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return SEED.equalsIgnoreCase(configured.trim()) ? SEED : SEQUENTIAL;
        }
        return SEQUENTIAL;
    }

    public static boolean isSeed(String mode) {
        return SEED.equalsIgnoreCase(mode);
    }

}
