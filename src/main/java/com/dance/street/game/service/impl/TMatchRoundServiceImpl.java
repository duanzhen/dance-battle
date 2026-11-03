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
import com.dance.street.game.domain.bo.TMatchRoundBo;
import com.dance.street.game.domain.vo.TMatchRoundVo;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITMatchRoundService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;

/**
 * 比赛轮次Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TMatchRoundServiceImpl implements ITMatchRoundService {

    private final TMatchRoundMapper baseMapper;
    private final TMatchMapper matchMapper;
    private final TStageMapper stageMapper;
    private final TRoundScoreMapper scoreMapper;

    /**
     * 查询比赛轮次
     *
     * @param id 主键
     * @return 比赛轮次
     */
    @Override
    public TMatchRoundVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询比赛轮次列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 比赛轮次分页列表
     */
    @Override
    public TableDataInfo<TMatchRoundVo> queryPageList(TMatchRoundBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TMatchRound> lqw = buildQueryWrapper(bo);
        Page<TMatchRoundVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的比赛轮次列表
     *
     * @param bo 查询条件
     * @return 比赛轮次列表
     */
    @Override
    public List<TMatchRoundVo> queryList(TMatchRoundBo bo) {
        LambdaQueryWrapper<TMatchRound> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TMatchRound> buildQueryWrapper(TMatchRoundBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TMatchRound> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TMatchRound::getId);
        lqw.eq(bo.getTournamentId() != null, TMatchRound::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getMatchId() != null, TMatchRound::getMatchId, bo.getMatchId());
        lqw.eq(bo.getRoundSequence() != null, TMatchRound::getRoundSequence, bo.getRoundSequence());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), TMatchRound::getStatus, bo.getStatus());
        return lqw;
    }

    /**
     * 新增比赛轮次
     *
     * @param bo 比赛轮次
     * @return 新增后的比赛轮次
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TMatchRoundVo insertByBo(TMatchRoundBo bo) {
        TMatchRound add = MapstructUtils.convert(bo, TMatchRound.class);
        if (add.getTournamentId() == null || add.getMatchId() == null) {
            throw new ServiceException("轮次必须归属赛事和场次");
        }
        TMatch match = requireMutableMatch(add.getMatchId());
        if (!Objects.equals(match.getTournamentId(), add.getTournamentId())) {
            throw new ServiceException("轮次与场次不属于同一赛事");
        }
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(StageConstants.MATCH_PENDING);
        }
        if (!StageConstants.MATCH_PENDING.equals(add.getStatus())) {
            throw new ServiceException("新建轮次必须从 PENDING 开始");
        }
        if (add.getRoundSequence() == null || add.getRoundSequence() < 1) {
            throw new ServiceException("轮次序号必须大于 0");
        }
        long duplicate = baseMapper.selectCount(Wrappers.<TMatchRound>lambdaQuery()
            .eq(TMatchRound::getMatchId, add.getMatchId())
            .eq(TMatchRound::getRoundSequence, add.getRoundSequence()));
        if (duplicate > 0) {
            throw new ServiceException("场次[{}]已存在第 {} 轮", add.getMatchId(), add.getRoundSequence());
        }
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TMatchRoundVo.class);
    }

    /**
     * 修改比赛轮次
     *
     * @param bo 比赛轮次
     * @return 修改后的比赛轮次
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TMatchRoundVo updateByBo(TMatchRoundBo bo) {
        TMatchRound update = MapstructUtils.convert(bo, TMatchRound.class);
        TMatchRound before = update.getId() == null ? null : baseMapper.selectById(update.getId());
        if (before == null) {
            throw new ServiceException("轮次不存在: {}", update.getId());
        }
        if (update.getTournamentId() != null
            && !Objects.equals(before.getTournamentId(), update.getTournamentId())) {
            throw new ServiceException("轮次不允许变更所属赛事");
        }
        if (update.getMatchId() != null && !Objects.equals(before.getMatchId(), update.getMatchId())) {
            throw new ServiceException("轮次不允许变更所属场次");
        }
        if (update.getRoundSequence() != null
            && !Objects.equals(before.getRoundSequence(), update.getRoundSequence())) {
            throw new ServiceException("轮次序号不允许通过通用更新变更");
        }
        if (update.getStatus() != null && !Objects.equals(before.getStatus(), update.getStatus())) {
            throw new ServiceException("轮次状态由场次生命周期统一推进，通用更新不能把 {} 改为 {}",
                before.getStatus(), update.getStatus());
        }
        requireMutableMatch(before.getMatchId());
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TMatchRoundVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TMatchRound entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除比赛轮次信息
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
                throw new ServiceException("轮次ID不能为空");
            }
            for (Long id : ids) {
                TMatchRound round = baseMapper.selectById(id);
                if (round == null) {
                    continue;
                }
                requireMutableMatch(round.getMatchId());
                long scores = scoreMapper.selectCount(Wrappers.<TRoundScore>lambdaQuery()
                    .eq(TRoundScore::getRoundId, id));
                if (scores > 0) {
                    throw new ServiceException("轮次[{}]已有打分记录，不能通过通用 CRUD 删除", id);
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    private TMatch requireMutableMatch(Long matchId) {
        TMatch match = matchId == null ? null : matchMapper.selectById(matchId);
        if (match == null) {
            throw new ServiceException("场次不存在: {}", matchId);
        }
        TStage stage = match.getStageId() == null ? null : stageMapper.selectById(match.getStageId());
        if (stage == null || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("赛段已开始，轮次结构已锁定");
        }
        if (!StageConstants.MATCH_PENDING.equals(match.getStatus())) {
            throw new ServiceException("场次已开始，轮次结构已锁定");
        }
        return match;
    }
}
