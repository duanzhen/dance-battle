package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 场次参赛方成绩的批量写入口:一次 UPDATE 覆盖整场。
 *
 * <p>结算/累计打分原先都是"按参赛方逐个 update",一场 64 人就是 64 条 SQL;
 * 这里按 competitorId 定位参赛行后走 {@link TMatchParticipantMapper#batchWriteScores}。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipantScoreWriter {

    private final TMatchParticipantMapper participantMapper;

    /** 一名参赛方要写的成绩(字段为 null 表示写 null) */
    public record ScorePatch(Long competitorId, BigDecimal scoreValue, Long rankInMatch, String outcomeStatus) {
    }

    /**
     * 批量回写本场参赛方成绩。
     *
     * @return 实际写入的行数
     */
    public int writeBatch(Long matchId, List<ScorePatch> patches) {
        if (matchId == null || patches == null || patches.isEmpty()) {
            return 0;
        }
        Map<Long, Long> participantIdByCompetitor = participantMapper
            .selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, matchId)
                .isNotNull(TMatchParticipant::getCompetitorId)
                .select(TMatchParticipant::getId, TMatchParticipant::getCompetitorId))
            .stream()
            .filter(p -> p.getCompetitorId() != null)
            .collect(Collectors.toMap(TMatchParticipant::getCompetitorId, TMatchParticipant::getId,
                (a, b) -> a));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScorePatch patch : patches) {
            Long participantId = patch.competitorId() == null
                ? null : participantIdByCompetitor.get(patch.competitorId());
            if (participantId == null) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", participantId);
            item.put("scoreValue", patch.scoreValue());
            item.put("rankInMatch", patch.rankInMatch());
            item.put("outcomeStatus", patch.outcomeStatus());
            // outcomeStatus 为 null = 老语义里的"不改结果列"(逐个 update 时 null 字段会被跳过)
            item.put("writeOutcome", patch.outcomeStatus() != null);
            items.add(item);
        }
        if (items.isEmpty()) {
            return 0;
        }
        participantMapper.batchWriteScores(items);
        log.debug("场次[{}]批量回写参赛方成绩 {} 行", matchId, items.size());
        return items.size();
    }
}
