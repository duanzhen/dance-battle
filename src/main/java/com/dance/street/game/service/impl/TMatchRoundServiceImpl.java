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
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.service.ITMatchRoundService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

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
    public TMatchRoundVo insertByBo(TMatchRoundBo bo) {
        TMatchRound add = MapstructUtils.convert(bo, TMatchRound.class);
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
    public TMatchRoundVo updateByBo(TMatchRoundBo bo) {
        TMatchRound update = MapstructUtils.convert(bo, TMatchRound.class);
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
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        return baseMapper.deleteByIds(ids) > 0;
    }
}
