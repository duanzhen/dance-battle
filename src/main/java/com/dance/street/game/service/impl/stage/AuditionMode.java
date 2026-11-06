package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 海选专属校验。生成前核对晋级名额,开赛前核对人人落圈且每圈有裁判。
 *
 * <p>原先挂在 {@code StageSetupHook}/{@code StageStartGuard} 上,全仓库只有海选实现,
 * 编排层按赛制直接调用本类。</p>
 */
@Component
@RequiredArgsConstructor
public class AuditionMode {

    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final AuditionCircleSupport auditionCircleSupport;

    /**
     * 生成对阵前:晋级名额必须能被配置圈数整除(显式每圈名额时只校验非负)。
     */
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

    /**
     * 生成/初始化完成之后、置 GAMING 之前:未退赛参赛方必须已落圈,每个圈至少一名裁判。
     *
     * <p>海选是按圈判的——有人没落圈会静默消失,圈上没裁判谁也打不了分、赛段结算不了。
     * 落圈由客户端(签到页)指定,后端不自动分配。</p>
     */
    public void assertPostGenerate(TStage stage) {
        assertAllAttached(stage);
        assertCirclesHaveReferees(stage);
    }

    private void assertAllAttached(TStage stage) {
        Long stageId = stage.getId();
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId)
            .ne(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.WITHDRAWN.getCode()));
        if (comps.isEmpty()) {
            return;
        }
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stageId)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        Set<Long> attached = matchIds.isEmpty() ? Set.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .select(TMatchParticipant::getCompetitorId))
                .stream().map(TMatchParticipant::getCompetitorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missed = comps.stream()
            .filter(c -> !attached.contains(c.getId()))
            .map(c -> c.getNumber() == null ? c.getName() : c.getName() + "(" + c.getNumber() + "号)")
            .toList();
        if (!missed.isEmpty()) {
            throw new ServiceException("海选有 {} 名参赛者尚未落圈,无法开始:{};请先为其指定圈子"
                + "(签到页选圈,或调用「参赛方落圈」接口)",
                missed.size(), String.join("、", missed));
        }
    }

    private void assertCirclesHaveReferees(TStage stage) {
        List<TMatch> circles = auditionCircleSupport.circles(stage);
        if (circles.isEmpty()) {
            return;
        }
        List<Long> circleIds = circles.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        Set<Long> circledWithReferee = circleIds.isEmpty() ? Set.of()
            : matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                    .in(TMatchReferee::getMatchId, circleIds)
                    .select(TMatchReferee::getMatchId))
                .stream().map(TMatchReferee::getMatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < circles.size(); i++) {
            TMatch circle = circles.get(i);
            if (!circledWithReferee.contains(circle.getId())) {
                missing.add(circle.getName() != null ? circle.getName() : ("第" + (i + 1) + "圈"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("海选有 {} 个圈还没有裁判,无法开始:{};"
                + "请给每圈指定裁判(赛段流程→海选配置→每圈裁判)",
                missing.size(), String.join("、", missing));
        }
    }
}
