package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.StageCircleLabelsVo;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 海选"参赛方 → 所在圈"标签的只读视图:一次批量算好,避免前端 5 个串行请求。
 *
 * <p>圈标签全站统一为 {@link StageFlowSupport#circleLabel(int)}(A圈/B圈…),
 * 内部存储的分区名仍是 {@code ZONE-k}。只查 3 条 SQL(赛段 / 圈场次 / 参赛方),
 * 不做任何写操作——建圈/绑裁判由配置保存与签到环节负责(见 {@code ensureAuditionCircles})。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class CircleLabelService {

    private static final String ZONE_PREFIX = "ZONE-";

    private final TStageMapper stageMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;

    public StageCircleLabelsVo labels(Long stageId) {
        if (stageId == null) {
            throw new ServiceException("赛段ID不能为空");
        }
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        StageCircleLabelsVo vo = new StageCircleLabelsVo();
        vo.setStageId(stage.getId());
        vo.setStageMode(stage.getStageMode());
        vo.setStageStatus(stage.getStatus());
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            return vo;
        }
        List<TMatch> zones = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .likeRight(TMatch::getDisplayZone, ZONE_PREFIX)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (zones.isEmpty()) {
            return vo;
        }
        // 场次 → 圈序号(同分加赛场次复用原圈分区,按同一圈计)
        Map<Long, Integer> zoneNoByMatch = new HashMap<>();
        int maxZone = 0;
        for (TMatch m : zones) {
            Integer no = zoneNo(m.getDisplayZone());
            if (no != null && m.getId() != null) {
                zoneNoByMatch.put(m.getId(), no);
                maxZone = Math.max(maxZone, no);
            }
        }
        vo.setCircleCount(maxZone);
        if (zoneNoByMatch.isEmpty()) {
            return vo;
        }
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, zoneNoByMatch.keySet().stream().toList())
            .isNotNull(TMatchParticipant::getCompetitorId)
            .select(TMatchParticipant::getMatchId, TMatchParticipant::getCompetitorId));
        for (TMatchParticipant p : parts) {
            Integer no = p.getMatchId() == null ? null : zoneNoByMatch.get(p.getMatchId());
            if (no == null || p.getCompetitorId() == null) {
                continue;
            }
            vo.getLabels().put(String.valueOf(p.getCompetitorId()), StageFlowSupport.circleLabel(no));
        }
        return vo;
    }

    private static Integer zoneNo(String displayZone) {
        if (displayZone == null || !displayZone.startsWith(ZONE_PREFIX)) {
            return null;
        }
        try {
            return Integer.valueOf(displayZone.substring(ZONE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
