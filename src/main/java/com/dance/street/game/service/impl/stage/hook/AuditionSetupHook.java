package com.dance.street.game.service.impl.stage.hook;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 海选生成前校验:晋级名额必须能被"配置圈数"整除(显式每圈名额时只校验非负)。
 */
@Component
public class AuditionSetupHook implements StageSetupHook {

    @Override
    public String stageMode() {
        return StageModeEnum.AUDITION.getCode();
    }

    @Override
    public void validateBeforeGenerate(TStage stage, RuleConfigHolder rc) {
        int advanceCount = StageFlowSupport.readStageAdvanceCount(stage);
        int cfgCircles = (rc != null && rc.getCircles() != null) ? Math.max(1, rc.getCircles()) : 1;
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        if (explicitQuota) {
            for (Integer q : perCircleCfg) {
                if (q == null || q < 0) {
                    throw new ServiceException("每圈晋级人数配置非法(不能为负): {}", perCircleCfg);
                }
            }
        } else if (advanceCount > 0 && advanceCount % cfgCircles != 0) {
            throw new ServiceException("海选总晋级数[{}]无法按{}圈均分,请调整晋级名额或圈数",
                advanceCount, cfgCircles);
        }
    }
}
