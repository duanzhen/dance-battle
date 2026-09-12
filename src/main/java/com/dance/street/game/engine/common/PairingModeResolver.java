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
     * <p>规则:显式配置优先;未配置时,只要本赛段的种子来自名次
     * (名单来源里含海选/排名赛,或直接前驱是海选/排名赛),就按种子摆位。</p>
     *
     * @param configured      ruleConfig.knockout.pairingMode(可为空)
     * @param seedsFromRanking 名单来源赛段中是否存在海选/排名赛
     * @param prevIsRanking   直接前驱赛段是否为海选/排名赛
     */
    public static String resolve(String configured, boolean seedsFromRanking, boolean prevIsRanking) {
        if (configured != null && !configured.isBlank()) {
            return SEED.equalsIgnoreCase(configured.trim()) ? SEED : SEQUENTIAL;
        }
        return (seedsFromRanking || prevIsRanking) ? SEED : SEQUENTIAL;
    }

    public static boolean isSeed(String mode) {
        return SEED.equalsIgnoreCase(mode);
    }

    /** 名单来源里是否有海选/排名赛(种子来自名次) */
    public static boolean seedsFromRanking(List<TStageRosterGroupBo> groups, java.util.function.Function<Long, TStage> stageLoader) {
        if (groups == null || groups.isEmpty()) {
            return false;
        }
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageLoader.apply(g.getSourceStageId());
            if (src != null && (StageModeEnum.AUDITION.getCode().equals(src.getStageMode())
                || StageModeEnum.RANK.getCode().equals(src.getStageMode()))) {
                return true;
            }
        }
        return false;
    }

    /** 直接前驱是否为海选/排名赛 */
    public static boolean prevIsRanking(TStage prev) {
        return prev != null
            && (StageModeEnum.AUDITION.getCode().equals(prev.getStageMode())
                || StageModeEnum.RANK.getCode().equals(prev.getStageMode()));
    }
}
