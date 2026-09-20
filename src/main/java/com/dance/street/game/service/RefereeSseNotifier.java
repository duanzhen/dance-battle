package com.dance.street.game.service;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.dromara.common.sse.dto.TournamentEventSseMessageDto;
import org.dromara.common.core.utils.AfterCommitUtils;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.mapper.TStageMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 裁判端 SSE 推送通知器。
 * 赛段/场次/打分发生变更时调用,按赛段分配的裁判定向推送,前端收到后刷新最新状态。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RefereeSseNotifier {

    private final ITRefereeStageService refereeStageService;
    private final TournamentEventSseEmitterManager tournamentEventSseEmitterManager;
    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;
    private final TMatchRefereeMapper matchRefereeMapper;

    /** 赛段级变更(开始/完成/晋级) */
    public void notifyStage(Long stageId, String type) {
        notify(stageId, null, type);
    }

    /** 场次级变更(打分/结算/重置) */
    public void notifyMatch(Long stageId, Long matchId, String type) {
        notify(stageId, matchId, type);
    }

    private void notify(Long stageId, Long matchId, String type) {
        // 事务内推送会把写锁攥到网络写完,改为提交后再推送(消息内容也按提交后的数据组装)
        AfterCommitUtils.runAfterCommit(() -> doNotify(stageId, matchId, type));
    }

    private void doNotify(Long stageId, Long matchId, String type) {
        try {
            if (stageId == null) {
                return;
            }
            List<Long> refereeIds = resolveAudience(stageId, matchId);
            if (CollUtil.isEmpty(refereeIds)) {
                return;
            }
            TStage stage = stageMapper.selectById(stageId);
            if (stage == null || stage.getTournamentId() == null) {
                return;
            }
            // 注意:雪花ID超 JS 安全整数,必须序列化为字符串,否则前端 JSON.parse 后精度丢失
            StringBuilder sb = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"tournamentId\":\"").append(stage.getTournamentId()).append('"')
                .append(",\"stageId\":\"").append(stageId).append('"');
            if (matchId != null) {
                sb.append(",\"matchId\":\"").append(matchId).append('"');
            }
            sb.append("}");
            TournamentEventSseMessageDto dto = new TournamentEventSseMessageDto();
            dto.setTournamentId(stage.getTournamentId());
            dto.setRefereeIds(refereeIds);
            dto.setMessage(sb.toString());
            tournamentEventSseEmitterManager.publishMessage(dto);
        } catch (Exception e) {
            // SSE 推送失败不影响业务主流程
            log.warn("裁判SSE推送失败 stageId={} matchId={} type={}: {}", stageId, matchId, type, e.getMessage());
        }
    }

    /**
     * 本次推送的裁判观众集合(赛段级 ∪ 场次/圈级)。
     *
     * <p><b>为什么必须并入圈级绑定</b>:海选是"按圈判"——主办方在圈上指定裁判
     * ({@code t_match_referee}),赛段级({@code t_referee_stage})可以是空的。
     * 此前只查赛段级、空集合直接 return,于是"开始海选后裁判端没有任何推送,
     * 必须手动刷新才出现打分"。</p>
     *
     * <p>场次级事件({@code matchId} 非空,如某圈结算/某场开赛)只发给该场的裁判,
     * 避免同赛段其他圈的裁判被无关事件打扰;赛段级事件发给赛段内全部圈裁判。</p>
     *
     * @return 去重后的裁判ID;为空表示本赛段没有任何裁判绑定,不推送
     */
    public List<Long> resolveAudience(Long stageId, Long matchId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(refereeStageService.getRefereeIdsByStageId(stageId));
        List<Long> targetMatchIds;
        if (matchId != null) {
            targetMatchIds = List.of(matchId);
        } else {
            targetMatchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stageId)
                    .select(TMatch::getId))
                .stream().map(TMatch::getId).toList();
        }
        if (!targetMatchIds.isEmpty()) {
            matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                    .in(TMatchReferee::getMatchId, targetMatchIds)
                    .select(TMatchReferee::getRefereeId))
                .forEach(r -> {
                    if (r.getRefereeId() != null) {
                        ids.add(r.getRefereeId());
                    }
                });
        }
        return new ArrayList<>(ids);
    }
}
