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
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.service.ITMatchParticipantService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

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

    /**
     * 查询场次参赛人员记录
     *
     * @param id 主键
     * @return 场次参赛人员记录
     */
    @Override
    public TMatchParticipantVo queryById(Long id){
        return baseMapper.selectVoById(id);
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
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TMatchParticipant> buildQueryWrapper(TMatchParticipantBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TMatchParticipant> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TMatchParticipant::getId);
        lqw.eq(bo.getTournamentId() != null, TMatchParticipant::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getMatchId() != null, TMatchParticipant::getMatchId, bo.getMatchId());
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
    public TMatchParticipantVo insertByBo(TMatchParticipantBo bo) {
        TMatchParticipant add = MapstructUtils.convert(bo, TMatchParticipant.class);
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
    public TMatchParticipantVo updateByBo(TMatchParticipantBo bo) {
        TMatchParticipant update = MapstructUtils.convert(bo, TMatchParticipant.class);
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
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        return baseMapper.deleteByIds(ids) > 0;
    }
}
