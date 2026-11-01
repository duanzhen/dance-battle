package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.domain.bo.MatchRefereeBo;
import com.dance.street.game.domain.vo.MatchRefereeVo;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchRefereeService;
import org.dromara.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 裁判-场次(圈)关联 Service 实现
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TMatchRefereeServiceImpl implements ITMatchRefereeService {

    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchMapper matchMapper;
    private final TRefereeMapper refereeMapper;
    private final TStageMapper stageMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assign(MatchRefereeBo bo) {
        TMatch match = bo.getMatchId() == null ? null : matchMapper.selectById(bo.getMatchId());
        if (match == null || !Objects.equals(match.getTournamentId(), bo.getTournamentId())) {
            throw new ServiceException("场次不存在或不属于当前赛事");
        }
        TStage stage = match.getStageId() == null ? null : stageMapper.selectById(match.getStageId());
        if (stage == null || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("赛段已开始，场次裁判配置已锁定");
        }
        List<Long> ids = bo.getRefereeIds() == null ? List.of()
            : bo.getRefereeIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() != bo.getRefereeIds().size()) {
            throw new ServiceException("裁判ID列表不能为空或包含空值");
        }
        List<TReferee> referees = refereeMapper.selectByIds(ids);
        if (referees.size() != ids.size()
            || referees.stream().anyMatch(r -> !Objects.equals(r.getTournamentId(), bo.getTournamentId()))) {
            throw new ServiceException("不能分配不存在或其他赛事的裁判");
        }
        // 每场(圈)可绑定多个裁判:先清旧关联再插入(全量替换)
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
            .eq(TMatchReferee::getMatchId, bo.getMatchId()));
        for (Long refereeId : bo.getRefereeIds()) {
            TMatchReferee mr = new TMatchReferee();
            mr.setMatchId(bo.getMatchId());
            mr.setRefereeId(refereeId);
            mr.setTournamentId(bo.getTournamentId());
            matchRefereeMapper.insert(mr);
        }
        log.info("场次[{}]已分配{}位裁判: {}", bo.getMatchId(), bo.getRefereeIds().size(), bo.getRefereeIds());
    }

    @Override
    public List<MatchRefereeVo> listByStageId(Long stageId) {
        if (stageId == null) {
            return List.of();
        }
        List<Long> matchIds = matchMapper.selectList(
                Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, stageId)
                    .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (matchIds.isEmpty()) {
            return List.of();
        }
        List<TMatchReferee> rows = matchRefereeMapper.selectList(
            Wrappers.<TMatchReferee>lambdaQuery()
                .in(TMatchReferee::getMatchId, matchIds));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> refereeIds = rows.stream()
            .map(TMatchReferee::getRefereeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nameById = refereeIds.isEmpty() ? Map.of()
            : refereeMapper.selectByIds(refereeIds).stream()
                .collect(Collectors.toMap(TReferee::getId, TReferee::getName));
        return rows.stream().map(r -> {
            MatchRefereeVo vo = new MatchRefereeVo();
            vo.setMatchId(r.getMatchId());
            vo.setRefereeId(r.getRefereeId());
            vo.setRefereeName(r.getRefereeId() == null ? null : nameById.get(r.getRefereeId()));
            return vo;
        }).toList();
    }
}
