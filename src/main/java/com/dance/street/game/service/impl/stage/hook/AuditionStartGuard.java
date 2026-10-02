package com.dance.street.game.service.impl.stage.hook;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
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
 * 海选开赛后守卫:所有未退赛参赛方必须已落圈、每个圈必须至少有一名裁判。
 *
 * <p>海选是按圈判的——有人没落圈会静默消失,圈上没裁判谁也打不了分、赛段结算不了。
 * 落圈由客户端(签到页)指定,后端不自动分配。</p>
 */
@Component
@RequiredArgsConstructor
public class AuditionStartGuard implements StageStartGuard {

    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final AuditionCircleSupport auditionCircleSupport;

    @Override
    public String stageMode() {
        return StageModeEnum.AUDITION.getCode();
    }

    @Override
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
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < circles.size(); i++) {
            TMatch circle = circles.get(i);
            long refs = matchRefereeMapper.selectCount(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, circle.getId()));
            if (refs == 0) {
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
