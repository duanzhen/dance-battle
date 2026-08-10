package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.service.ITRefereeStageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 裁判-赛段关联 Service 实现
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TRefereeStageServiceImpl implements ITRefereeStageService {

    private final TRefereeStageMapper refereeStageMapper;

    @Override
    public List<Long> getRefereeIdsByStageId(Long stageId) {
        return refereeStageMapper.selectList(
                Wrappers.<TRefereeStage>lambdaQuery()
                    .eq(TRefereeStage::getStageId, stageId))
            .stream()
            .map(TRefereeStage::getRefereeId)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignReferees(StageRefereeBo bo) {
        // 删除该赛段的旧关联
        refereeStageMapper.delete(
            Wrappers.<TRefereeStage>lambdaQuery()
                .eq(TRefereeStage::getStageId, bo.getStageId()));

        // 批量插入新关联
        if (bo.getRefereeIds() != null && !bo.getRefereeIds().isEmpty()) {
            for (Long refereeId : bo.getRefereeIds()) {
                TRefereeStage rs = new TRefereeStage();
                rs.setRefereeId(refereeId);
                rs.setStageId(bo.getStageId());
                rs.setTournamentId(bo.getTournamentId());
                refereeStageMapper.insert(rs);
            }
        }
        log.info("赛段[{}]已分配 {} 位裁判", bo.getStageId(), bo.getRefereeIds() != null ? bo.getRefereeIds().size() : 0);
    }

    @Override
    public List<Long> getStageIdsByRefereeId(Long refereeId) {
        return refereeStageMapper.selectList(
                Wrappers.<TRefereeStage>lambdaQuery()
                    .eq(TRefereeStage::getRefereeId, refereeId))
            .stream()
            .map(TRefereeStage::getStageId)
            .collect(Collectors.toList());
    }
}
