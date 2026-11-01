package com.dance.street.game.service.impl;

import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import com.dance.street.game.domain.vo.TMatchParticipantVo;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.ITMatchParticipantService;
import com.dance.street.game.engine.common.StageModeProfiles;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.HashMap;

/**
 * 场次参赛人员记录Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TMatchParticipantServiceImpl implements ITMatchParticipantService {

    private final TMatchParticipantMapper baseMapper;
    private final TMatchMapper matchMapper;
    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchRoundMapper roundMapper;
    private final TRoundScoreMapper scoreMapper;

    /**
     * 查询场次参赛人员记录
     *
     * @param id 主键
     * @return 场次参赛人员记录
     */
    @Override
    public TMatchParticipantVo queryById(Long id){
        TMatchParticipantVo vo = baseMapper.selectVoById(id);
        maskRankScores(List.of(vo));
        return vo;
    }

    /**
     * 分页查询场次参赛人员记录列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 场次参赛人员记录分页列表
     */
    @Override
    public TableDataInfo<TMatchParticipantVo> queryPageList(TMatchParticipantBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TMatchParticipant> lqw = buildQueryWrapper(bo);
        Page<TMatchParticipantVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        maskRankScores(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的场次参赛人员记录列表
     *
     * @param bo 查询条件
     * @return 场次参赛人员记录列表
     */
    @Override
    public List<TMatchParticipantVo> queryList(TMatchParticipantBo bo) {
        LambdaQueryWrapper<TMatchParticipant> lqw = buildQueryWrapper(bo);
        List<TMatchParticipantVo> list = baseMapper.selectVoList(lqw);
        maskRankScores(list);
        return list;
    }

    /**
     * 排名赛公布控制:MANUAL/BATCH 模式且赛段未结算(SETTLED)前,
     * 隐藏参赛方总分/排名,保证大屏与外部接口在公布前看不到结果。
     */
    private void maskRankScores(List<TMatchParticipantVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        // 场次/赛段一次批量取回:此前每个 distinct matchId 各查一次场次与赛段,
        // 对战树/大屏一次加载就是几十条 SQL(与"按场次逐个请求"叠在一起更明显)
        List<Long> matchIds = list.stream()
            .map(TMatchParticipantVo::getMatchId).filter(Objects::nonNull).distinct().toList();
        if (matchIds.isEmpty()) {
            return;
        }
        Map<Long, TMatch> matchById = matchMapper.selectByIds(matchIds).stream()
            .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
        List<Long> stageIds = matchById.values().stream()
            .map(TMatch::getStageId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TStage> stageById = stageIds.isEmpty() ? Map.of()
            : stageMapper.selectByIds(stageIds).stream()
                .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        Map<Long, Boolean> hiddenByMatch = new HashMap<>();
        for (Map.Entry<Long, TMatch> entry : matchById.entrySet()) {
            TMatch m = entry.getValue();
            TStage s = m.getStageId() == null ? null : stageById.get(m.getStageId());
            boolean hidden = false;
            if (s != null && StageModeProfiles.of(s.getStageMode()).rank()
                && !StageConstants.STAGE_SETTLED.equals(s.getStatus())) {
                RuleConfigHolder rc = RuleConfigParser.parse(s.getRuleConfig());
                hidden = rc != null && rc.getPublishMode() != null
                    && !"AUTO".equalsIgnoreCase(rc.getPublishMode());
            }
            hiddenByMatch.put(entry.getKey(), hidden);
        }
        for (TMatchParticipantVo vo : list) {
            if (vo.getMatchId() != null && Boolean.TRUE.equals(hiddenByMatch.get(vo.getMatchId()))) {
                vo.setScoreValue(null);
                vo.setRankInMatch(null);
            }
        }
    }

    private LambdaQueryWrapper<TMatchParticipant> buildQueryWrapper(TMatchParticipantBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TMatchParticipant> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TMatchParticipant::getId);
        lqw.eq(bo.getTournamentId() != null, TMatchParticipant::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getMatchId() != null, TMatchParticipant::getMatchId, bo.getMatchId());
        // 按赛段批量取参赛方(对战树/大屏):一次拿到该赛段全部场次的行,前端不必逐场请求
        if (bo.getStageId() != null) {
            List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                    .eq(TMatch::getStageId, bo.getStageId())
                    .select(TMatch::getId))
                .stream().map(TMatch::getId).toList();
            if (matchIds.isEmpty()) {
                lqw.eq(TMatchParticipant::getMatchId, -1L); // 该赛段还没有场次:返回空而不是全表
            } else {
                lqw.in(TMatchParticipant::getMatchId, matchIds);
            }
        }
        lqw.eq(bo.getCompetitorId() != null, TMatchParticipant::getCompetitorId, bo.getCompetitorId());
        lqw.eq(bo.getDisplaySlotIndex() != null, TMatchParticipant::getDisplaySlotIndex, bo.getDisplaySlotIndex());
        lqw.eq(bo.getScoreValue() != null, TMatchParticipant::getScoreValue, bo.getScoreValue());
        lqw.eq(bo.getRankInMatch() != null, TMatchParticipant::getRankInMatch, bo.getRankInMatch());
        lqw.eq(StringUtils.isNotBlank(bo.getOutcomeStatus()), TMatchParticipant::getOutcomeStatus, bo.getOutcomeStatus());
        return lqw;
    }

    /**
     * 新增场次参赛人员记录
     *
     * @param bo 场次参赛人员记录
     * @return 新增后的场次参赛人员记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TMatchParticipantVo insertByBo(TMatchParticipantBo bo) {
        TMatchParticipant add = MapstructUtils.convert(bo, TMatchParticipant.class);
        if (add.getTournamentId() == null || add.getMatchId() == null) {
            throw new ServiceException("参赛明细必须归属赛事和场次");
        }
        TMatch match = matchMapper.selectById(add.getMatchId());
        if (match == null || !Objects.equals(match.getTournamentId(), add.getTournamentId())) {
            throw new ServiceException("参赛明细与场次不属于同一赛事");
        }
        if (add.getCompetitorId() != null) {
            TCompetitor competitor = competitorMapper.selectById(add.getCompetitorId());
            if (competitor == null
                || !Objects.equals(competitor.getStageId(), match.getStageId())
                || !Objects.equals(competitor.getTournamentId(), match.getTournamentId())) {
                throw new ServiceException("参赛方不属于场次所在赛段");
            }
        }
        guardParticipantMutable(add.getMatchId());
        if (StringUtils.isBlank(add.getSlotKind())) {
            add.setSlotKind(StageConstants.SLOT_PLAYER);
        }
        if (StringUtils.isBlank(add.getOutcomeStatus())) {
            add.setOutcomeStatus(MatchOutcomeEnum.PENDING.getCode());
        }
        guardParticipantDuplicates(add, null);
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TMatchParticipantVo.class);
    }

    /**
     * 修改场次参赛人员记录
     *
     * @param bo 场次参赛人员记录
     * @return 修改后的场次参赛人员记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TMatchParticipantVo updateByBo(TMatchParticipantBo bo) {
        TMatchParticipant update = MapstructUtils.convert(bo, TMatchParticipant.class);
        TMatchParticipant before = update.getId() == null ? null : baseMapper.selectById(update.getId());
        if (before == null) {
            throw new ServiceException("参赛明细不存在: {}", update.getId());
        }
        if (update.getTournamentId() != null
            && !Objects.equals(before.getTournamentId(), update.getTournamentId())) {
            throw new ServiceException("参赛明细不允许变更所属赛事");
        }
        if (update.getMatchId() != null && !Objects.equals(before.getMatchId(), update.getMatchId())) {
            throw new ServiceException("参赛明细不允许变更所属场次");
        }
        if (update.getCompetitorId() != null
            && !Objects.equals(before.getCompetitorId(), update.getCompetitorId())) {
            throw new ServiceException("参赛明细不允许通过通用更新更换参赛方");
        }
        guardParticipantMutable(before.getMatchId());
        guardParticipantDuplicates(update, before.getId());
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TMatchParticipantVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TMatchParticipant entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除场次参赛人员记录信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (Boolean.TRUE.equals(isValid)) {
            if (ids == null || ids.isEmpty()) {
                throw new ServiceException("参赛明细ID不能为空");
            }
            for (Long id : ids) {
                TMatchParticipant participant = baseMapper.selectById(id);
                if (participant == null) {
                    continue;
                }
                guardParticipantMutable(participant.getMatchId());
                if (participant.getCompetitorId() != null) {
                    List<Long> roundIds = roundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                            .eq(TMatchRound::getMatchId, participant.getMatchId())
                            .select(TMatchRound::getId))
                        .stream().map(TMatchRound::getId).filter(Objects::nonNull).toList();
                    long scores = roundIds.isEmpty() ? 0 : scoreMapper.selectCount(
                        Wrappers.<TRoundScore>lambdaQuery()
                            .in(TRoundScore::getRoundId, roundIds)
                            .eq(TRoundScore::getCompetitorId, participant.getCompetitorId()));
                    if (scores > 0) {
                        throw new ServiceException("参赛方[{}]已在本场留有打分，不能删除参赛明细",
                            participant.getCompetitorId());
                    }
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    private void guardParticipantMutable(Long matchId) {
        TMatch match = matchId == null ? null : matchMapper.selectById(matchId);
        TStage stage = match == null ? null : stageMapper.selectById(match.getStageId());
        if (stage == null || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("赛段已开始，场次参赛明细已锁定");
        }
        if (!StageConstants.MATCH_PENDING.equals(match.getStatus())) {
            throw new ServiceException("场次已开始，参赛明细已锁定");
        }
    }

    private void guardParticipantDuplicates(TMatchParticipant participant, Long excludedId) {
        if (participant.getCompetitorId() != null) {
            var query = Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, participant.getMatchId())
                .eq(TMatchParticipant::getCompetitorId, participant.getCompetitorId());
            if (excludedId != null) {
                query.ne(TMatchParticipant::getId, excludedId);
            }
            if (baseMapper.selectCount(query) > 0) {
                throw new ServiceException("参赛方[{}]已在该场次中", participant.getCompetitorId());
            }
        }
        if (participant.getDisplaySlotIndex() != null) {
            var query = Wrappers.<TMatchParticipant>lambdaQuery()
                .eq(TMatchParticipant::getMatchId, participant.getMatchId())
                .eq(TMatchParticipant::getDisplaySlotIndex, participant.getDisplaySlotIndex());
            if (excludedId != null) {
                query.ne(TMatchParticipant::getId, excludedId);
            }
            if (baseMapper.selectCount(query) > 0) {
                throw new ServiceException("场次[{}]的显示槽位 {} 已被占用",
                    participant.getMatchId(), participant.getDisplaySlotIndex());
            }
        }
    }

}
