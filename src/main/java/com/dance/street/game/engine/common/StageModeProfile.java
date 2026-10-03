package com.dance.street.game.engine.common;

import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.utils.StringUtils;

import java.util.Set;

/**
 * 赛段模式的"数据型"画像:一种赛制一条记录,集中声明模式之间的<b>取值差异</b>。
 *
 * <p>只有"取值不同"的差异放这里(是否逐选手、是否生成对阵、默认 matchMode、排序口径…);
 * "算法不同/要查库"的差异仍走接口:已有的 {@code StageGenerator}/{@code StageSettler},
 * 以及 service 侧的 {@code StageSetupHook}/{@code StageStartGuard}。</p>
 *
 * @param mode   赛制
 * @param setup  初始化/生成对阵相关
 * @param run    开赛/推进相关
 * @param result 提交/结算相关
 * @param view   读模型/视图相关
 */
public record StageModeProfile(
    StageModeEnum mode,
    Setup setup,
    Run run,
    Result result,
    View view
) {

    public String code() {
        return mode.getCode();
    }

    // ===== 赛制身份(替代编排层里的 mode 字符串比较)=====

    public boolean audition() {
        return mode == StageModeEnum.AUDITION;
    }

    public boolean rank() {
        return mode == StageModeEnum.RANK;
    }

    public boolean knockout() {
        return mode == StageModeEnum.KNOCKOUT;
    }

    public boolean arena() {
        return mode == StageModeEnum.ARENA;
    }

    /** 初始化 / 生成对阵。 */
    public record Setup(
        SeedOrder seedOrder,
        GeneratePolicy generatePolicy,
        String rejectMessage,
        MatchModePolicy matchMode,
        Set<Trait> traits
    ) {
        public boolean has(Trait trait) {
            return traits.contains(trait);
        }

        /** 结构/落位相关的布尔差异(取值型,统一放这里)。 */
        public enum Trait {
            /** 保留中间态排好的座位(淘汰赛座位是"位置")。 */
            KEEPS_SEATS,
            /** 对阵规模按 teamCountStart 兜底(淘汰赛)。 */
            SLOTS_FROM_TEAM_COUNT,
            /** 按"圈"组织(海选):持久化分圈方式、生成后绑圈裁判。 */
            CIRCLE_SPLIT,
            /** 生成/开赛前必须先配置圈(海选)。 */
            REQUIRES_CIRCLES,
            /** 需要解析淘汰赛首轮配对方式。 */
            RESOLVES_KNOCKOUT_PAIRING
        }
    }

    /** 开赛 / 赛中推进。 */
    public record Run(
        boolean autoStartFirstMatch,
        boolean singleActiveMatch
    ) {
    }

    /** 提交 / 结算语义。 */
    public record Result(
        boolean perCompetitor,
        boolean writeBackSubmittedOnly
    ) {
    }

    /** 读模型 / 视图支持范围。 */
    public record View(
        boolean preBracket,
        boolean rankDetail,
        boolean advancementAdjustment
    ) {
    }

    public enum SeedOrder {
        /** 按号码数值升序(号码即种子顺序)。 */
        BY_NUMBER,
        /** 按 seedRank 升序,NULL 最后。 */
        BY_SEED,
        /** 先按 seedRank,再按号码(擂台:显式落位优先)。 */
        SEED_THEN_NUMBER
    }

    public enum GeneratePolicy {
        GENERATE,
        /** 不生成也不报错(自由对抗,场次由导播手动加)。 */
        SKIP_SILENT,
        /** 不生成并报错(擂台赛,场次由导播逐场创建)。 */
        REJECT
    }

    public enum MatchModePolicy {
        VOTING,
        RANKING,
        /** 取规则配置里的 matchMode,缺省回退(通常 STANDARD)。 */
        FROM_CONFIG;

        public String resolve(String configured, String fallback) {
            return switch (this) {
                case VOTING -> MatchModeEnum.VOTING.getCode();
                case RANKING -> MatchModeEnum.RANKING.getCode();
                case FROM_CONFIG -> StringUtils.isNotBlank(configured) ? configured : fallback;
            };
        }
    }
}
