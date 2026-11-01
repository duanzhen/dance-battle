package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITRefereeStageService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchMapper matchMapper;
    private final TStageMapper stageMapper;
    private final TRefereeMapper refereeMapper;

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
        TStage stage = bo.getStageId() == null ? null : stageMapper.selectById(bo.getStageId());
        if (stage == null || !Objects.equals(stage.getTournamentId(), bo.getTournamentId())) {
            throw new ServiceException("赛段不存在或不属于当前赛事");
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]已开始，裁判配置已锁定", stage.getName());
        }
        List<Long> ids = bo.getRefereeIds() == null ? List.of()
            : bo.getRefereeIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() != (bo.getRefereeIds() == null ? 0 : bo.getRefereeIds().size())) {
            throw new ServiceException("裁判ID不能为空");
        }
        if (!ids.isEmpty()) {
            List<TReferee> referees = refereeMapper.selectByIds(ids);
            if (referees.size() != ids.size()
                || referees.stream().anyMatch(r -> !Objects.equals(r.getTournamentId(), bo.getTournamentId()))) {
                throw new ServiceException("不能分配不存在或其他赛事的裁判");
            }
        }
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
        // 赛段级绑定 + 圈级绑定(t_match_referee):海选按圈判,主办方可以只在圈上指定裁判,
        // 此时赛段级为空——只认赛段级会让裁判页直接显示"暂未分配赛段"。
        LinkedHashSet<Long> stageIds = new LinkedHashSet<>(refereeStageMapper.selectList(
                Wrappers.<TRefereeStage>lambdaQuery()
                    .eq(TRefereeStage::getRefereeId, refereeId))
            .stream()
            .map(TRefereeStage::getStageId)
            .collect(Collectors.toList()));
        List<Long> myMatchIds = matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getRefereeId, refereeId)
                .select(TMatchReferee::getMatchId))
            .stream().map(TMatchReferee::getMatchId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!myMatchIds.isEmpty()) {
            matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .in(TMatch::getId, myMatchIds)
                    .select(TMatch::getStageId))
                .forEach(m -> {
                    if (m.getStageId() != null) {
                        stageIds.add(m.getStageId());
                    }
                });
        }
        return new ArrayList<>(stageIds);
    }

    @Override
    public java.util.Map<Long, List<Long>> getRefereeIdsByStageIds(java.util.Collection<Long> stageIds) {
        if (stageIds == null || stageIds.isEmpty()) {
            return java.util.Map.of();
        }
        return refereeStageMapper.selectList(Wrappers.<TRefereeStage>lambdaQuery()
                .in(TRefereeStage::getStageId, stageIds.stream().distinct().toList()))
            .stream()
            .filter(r -> r.getStageId() != null && r.getRefereeId() != null)
            .collect(Collectors.groupingBy(TRefereeStage::getStageId,
                Collectors.mapping(TRefereeStage::getRefereeId, Collectors.toList())));
    }
}
