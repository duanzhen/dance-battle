package com.dance.street.game.service.impl.stage.handler;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.StageModeProfile;
import com.dance.street.game.engine.common.StageModeProfiles;
import com.dance.street.game.engine.generator.BracketPlan;
import com.dance.street.game.service.impl.settle.StageSettleOutcome;
import org.dromara.common.core.exception.ServiceException;

import java.util.List;

/**
 * 赛制策略统一接口:一种赛制一个实现,由 {@link StageHandlerRegistry} 按 stageMode 解析。
 *
 * <p>编排层({@link StageCommands} / Setup / Run / Settle)只依赖本接口与 Registry,
 * 不 import 具体模式实现,也不再经 SettlerRegistry / GeneratorFactory 分派。</p>
 *
 * <p>Handler <b>不修改</b> {@code t_stage.status}。取值型差异经 {@link #capabilities()};
 * 生成对阵树经 {@link #generateBracket};结算经 {@link #settle}。</p>
 *
 * @author duane
 */
public interface StageHandler {

    /** 负责的赛制({@code StageModeEnum.code}) */
    String stageMode();

    /** 取值型能力(原 StageModeProfiles 行);编排用它替代 Profiles.of 分支。 */
    default StageModeProfile capabilities() {
        return StageModeProfiles.of(stageMode());
    }

    /**
     * 模式专属初始化钩子。共享锁名单/排种子仍由 StageSetupService 执行;
     * 默认空——不要把每赛制都无关的逻辑塞进来。
     */
    default void initialize(InitializeStageBo bo) {
        // no-op
    }

    /**
     * 模式专属生成对阵钩子。共享生成流水线仍由 StageSetupService 落库;
     * 树计划走 {@link #generateBracket}。默认空。
     */
    default void generateMatches(GenerateMatchesBo bo) {
        // no-op
    }

    /**
     * 纯计算产出对阵树。仅 {@code GeneratePolicy.GENERATE} 的 Handler 实现;
     * 其他模式默认抛错(编排应先读 capabilities 再调用)。
     */
    default BracketPlan generateBracket(List<Long> seededCompetitorIds, RuleConfigHolder ruleConfig) {
        throw new ServiceException("赛制[{}]不支持生成对阵树", stageMode());
    }

    /** DRAFT→GAMING 翻转前;默认空。 */
    default void beforeStart(TStage stage) {
        // no-op
    }

    /**
     * DRAFT→GAMING 翻转后。擂台在此开首场;其他模式默认空。
     *
     * @param stageId 已进入 GAMING 的赛段
     */
    default void afterStart(Long stageId) {
        // no-op
    }

    /**
     * 结算赛段(写 competitor outcome/rank 等),不改 stage.status。
     *
     * @return closable / pending / tiebreaker
     */
    StageSettleOutcome settle(TStage stage);

    /**
     * 模式专属撤销钩子。共享清 match/回退状态仍由 StageSettlementService 执行;默认空。
     */
    default void resetToDraft(Long stageId) {
        // no-op
    }
}
