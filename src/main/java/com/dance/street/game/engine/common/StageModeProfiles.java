package com.dance.street.game.engine.common;

import com.dance.street.game.engine.common.StageModeProfile.GeneratePolicy;
import com.dance.street.game.engine.common.StageModeProfile.MatchModePolicy;
import com.dance.street.game.engine.common.StageModeProfile.Result;
import com.dance.street.game.engine.common.StageModeProfile.Run;
import com.dance.street.game.engine.common.StageModeProfile.SeedOrder;
import com.dance.street.game.engine.common.StageModeProfile.Setup;
import com.dance.street.game.engine.common.StageModeProfile.Setup.Trait;
import com.dance.street.game.engine.common.StageModeProfile.View;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛制画像表:一种赛制一行,新增赛制照抄一行即可。
 *
 * <p>{@link #uncoveredModes()} 供启动自检/测试断言,漏配直接暴露。</p>
 */
public final class StageModeProfiles {

    private static final Map<String, StageModeProfile> BY_CODE = build();

    private StageModeProfiles() {
    }

    /** 按赛制 code 取画像;未知赛制直接报错。 */
    public static StageModeProfile of(String code) {
        StageModeProfile profile = BY_CODE.get(code);
        if (profile == null) {
            throw new ServiceException("未知的赛段模式: {}", code);
        }
        return profile;
    }

    /** 尚未配置画像的赛制(启动自检/测试断言用)。 */
    public static List<String> uncoveredModes() {
        return Arrays.stream(StageModeEnum.values())
            .map(StageModeEnum::getCode)
            .filter(code -> !BY_CODE.containsKey(code))
            .toList();
    }

    private static Map<String, StageModeProfile> build() {
        Map<String, StageModeProfile> map = new HashMap<>();

        // 海选:逐选手、按号码、按圈、签到必须落圈、按圈分配晋级名额
        put(map, StageModeEnum.AUDITION,
            new Setup(SeedOrder.BY_NUMBER, GeneratePolicy.GENERATE, null, MatchModePolicy.VOTING,
                Set.of(Trait.CIRCLE_SPLIT, Trait.REQUIRES_CIRCLES)),
            new Run(false, false),
            new Result(true, true),
            new View(false, false, false));

        // 排名赛:逐选手、按号码、多维度排名打分、支持排名明细与同分调整
        put(map, StageModeEnum.RANK,
            new Setup(SeedOrder.BY_NUMBER, GeneratePolicy.GENERATE, null, MatchModePolicy.RANKING, Set.of()),
            new Run(false, false),
            new Result(true, false),
            new View(false, true, true));

        // 淘汰赛:座位是"位置"、按计划规模兜底、逐场进行中、支持预排
        put(map, StageModeEnum.KNOCKOUT,
            new Setup(SeedOrder.BY_SEED, GeneratePolicy.GENERATE, null, MatchModePolicy.FROM_CONFIG,
                Set.of(Trait.KEEPS_SEATS, Trait.SLOTS_FROM_TEAM_COUNT, Trait.RESOLVES_KNOCKOUT_PAIRING)),
            new Run(false, true),
            new Result(false, false),
            new View(true, false, false));

        // 小组赛:默认排序、单场判定,支持同分调整
        put(map, StageModeEnum.GROUP,
            new Setup(SeedOrder.BY_SEED, GeneratePolicy.GENERATE, null, MatchModePolicy.FROM_CONFIG, Set.of()),
            new Run(false, false),
            new Result(false, false),
            new View(false, false, true));

        // 擂台赛:不生成对阵树、开赛自动开第一场、显式落位优先、支持预排
        put(map, StageModeEnum.ARENA,
            new Setup(SeedOrder.SEED_THEN_NUMBER, GeneratePolicy.REJECT,
                "擂台赛不生成对阵,开始赛段后由导播台逐场创建对决",
                MatchModePolicy.FROM_CONFIG, Set.of()),
            new Run(true, false),
            new Result(false, false),
            new View(true, false, false));

        // 自由对抗:不生成对阵,场次由导播手动添加
        put(map, StageModeEnum.FREE_MATCH,
            new Setup(SeedOrder.BY_SEED, GeneratePolicy.SKIP_SILENT, null, MatchModePolicy.FROM_CONFIG, Set.of()),
            new Run(false, false),
            new Result(false, false),
            new View(false, false, false));

        return Map.copyOf(map);
    }

    private static void put(Map<String, StageModeProfile> map, StageModeEnum mode,
                            Setup setup, Run run, Result result, View view) {
        map.put(mode.getCode(), new StageModeProfile(mode, setup, run, result, view));
    }
}
