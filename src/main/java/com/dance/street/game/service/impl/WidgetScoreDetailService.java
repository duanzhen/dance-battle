package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.TVisScreen;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.vo.ScoreDetailWidgetVo;
import com.dance.street.game.domain.vo.WidgetScoreDetailTargetVo;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.mapper.TVisScreenMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.TournamentEventNotifier;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评分详情组件的推送/查询:校验组件类型与归属后写入跨实例缓存,并广播给大屏。
 *
 * @author duane
 */
@RequiredArgsConstructor
@Service
public class WidgetScoreDetailService {

    private final WidgetScoreDetailStore store;
    private final TVisWidgetMapper widgetMapper;
    private final TVisSceneMapper sceneMapper;
    private final TVisScreenMapper screenMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TournamentEventNotifier tournamentEventNotifier;

    /**
     * 导播台推送或清除某个评分详情组件的目标。
     * {@code matchId} 与 {@code competitorId} 都为空时清除;只传其中一个视为参数不完整。
     */
    public void setTarget(Long tournamentId, Long widgetId, Long matchId, Long competitorId) {
        TVisWidget widget = requireScoreDetailWidget(tournamentId, widgetId);
        boolean clearing = matchId == null && competitorId == null;
        if (!clearing && (matchId == null || competitorId == null)) {
            throw new ServiceException("请同时指定场次和选手,或同时留空以清除推送");
        }
        if (clearing) {
            store.mark(widgetId, null, null);
            tournamentEventNotifier.notify(widget.getTournamentId(), null, null, "score-detail");
            return;
        }
        TMatch match = matchMapper.selectById(matchId);
        if (match == null || !Objects.equals(match.getTournamentId(), tournamentId)) {
            throw new ServiceException("场次不存在或不属于当前赛事");
        }
        long attached = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, competitorId)
            .eq(TMatchParticipant::getTournamentId, match.getTournamentId()));
        if (attached == 0) {
            throw new ServiceException("参赛方[{}]不属于场次[{}]，不能推送到评分详情",
                competitorId, match.getId());
        }
        store.mark(widgetId, matchId, competitorId);
        tournamentEventNotifier.notify(match.getTournamentId(), match.getStageId(), matchId, "score-detail");
    }

    /** 导播台查询某个组件当前推送目标;未推送返回 null */
    public WidgetScoreDetailTargetVo getTarget(Long tournamentId, Long widgetId) {
        requireScoreDetailWidget(tournamentId, widgetId);
        return store.current(widgetId);
    }

    /**
     * 大屏公开读取某个组件的当前推送目标。
     * 组件不存在或类型不对时返回 null(不抛错,大屏显示「暂无推送」即可)。
     */
    public WidgetScoreDetailTargetVo getTargetPublic(Long widgetId) {
        if (widgetId == null) {
            return null;
        }
        TVisWidget widget = widgetMapper.selectById(widgetId);
        if (widget == null || !WidgetScoreDetailStore.WIDGET_TYPE.equals(widget.getType())) {
            return null;
        }
        return store.current(widgetId);
    }

    /** 当前赛事下全部评分详情组件(供导播台选择推送目标) */
    public List<ScoreDetailWidgetVo> listForTournament(Long tournamentId) {
        if (tournamentId == null) {
            return List.of();
        }
        List<TVisWidget> widgets = widgetMapper.selectList(Wrappers.<TVisWidget>lambdaQuery()
            .eq(TVisWidget::getTournamentId, tournamentId)
            .eq(TVisWidget::getType, WidgetScoreDetailStore.WIDGET_TYPE)
            .orderByAsc(TVisWidget::getSceneId)
            .orderByAsc(TVisWidget::getId));
        if (widgets.isEmpty()) {
            return List.of();
        }
        List<Long> sceneIds = widgets.stream().map(TVisWidget::getSceneId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> sceneNameById = sceneIds.isEmpty() ? Map.of()
            : sceneMapper.selectByIds(sceneIds).stream()
                .collect(Collectors.toMap(TVisScene::getId, s -> s.getName() == null ? "" : s.getName(), (a, b) -> a));
        Map<Long, String> screenNameByScene = new LinkedHashMap<>();
        if (!sceneIds.isEmpty()) {
            List<TVisScreen> screens = screenMapper.selectList(Wrappers.<TVisScreen>lambdaQuery()
                .eq(TVisScreen::getTournamentId, tournamentId)
                .in(TVisScreen::getCurrentSceneId, sceneIds));
            for (TVisScreen screen : screens) {
                if (screen.getCurrentSceneId() == null) {
                    continue;
                }
                String name = screen.getName() == null ? "" : screen.getName();
                screenNameByScene.merge(screen.getCurrentSceneId(), name, (a, b) -> {
                    if (a == null || a.isBlank()) {
                        return b;
                    }
                    if (b == null || b.isBlank() || a.equals(b)) {
                        return a;
                    }
                    return a + "、" + b;
                });
            }
        }
        List<ScoreDetailWidgetVo> out = new ArrayList<>(widgets.size());
        for (TVisWidget w : widgets) {
            ScoreDetailWidgetVo vo = new ScoreDetailWidgetVo();
            vo.setId(w.getId());
            vo.setName(w.getName());
            vo.setSceneId(w.getSceneId());
            vo.setSceneName(w.getSceneId() == null ? null : sceneNameById.get(w.getSceneId()));
            vo.setScreenName(w.getSceneId() == null ? null : screenNameByScene.get(w.getSceneId()));
            WidgetScoreDetailTargetVo target = store.current(w.getId());
            if (target != null) {
                vo.setMatchId(target.getMatchId());
                vo.setCompetitorId(target.getCompetitorId());
            }
            out.add(vo);
        }
        return out;
    }

    private TVisWidget requireScoreDetailWidget(Long tournamentId, Long widgetId) {
        if (widgetId == null) {
            throw new ServiceException("组件不存在");
        }
        TVisWidget widget = widgetMapper.selectById(widgetId);
        if (widget == null) {
            throw new ServiceException("组件不存在");
        }
        if (!Objects.equals(widget.getTournamentId(), tournamentId)) {
            throw new ServiceException("组件不属于当前赛事");
        }
        if (!WidgetScoreDetailStore.WIDGET_TYPE.equals(widget.getType())) {
            throw new ServiceException("该组件不是评分详情");
        }
        return widget;
    }
}
