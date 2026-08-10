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
import com.dance.street.game.domain.bo.TRoundScoreBo;
import com.dance.street.game.domain.vo.TRoundScoreVo;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.service.ITRoundScoreService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 轮次打分结果Service业务层处理
 *
 * @author duane
 * @date 2026-01-11
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TRoundScoreServiceImpl implements ITRoundScoreService {

    private final TRoundScoreMapper baseMapper;

    /**
     * 查询轮次打分结果
     *
     * @param id 主键
     * @return 轮次打分结果
     */
    @Override
    public TRoundScoreVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询轮次打分结果列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 轮次打分结果分页列表
     */
    @Override
    public TableDataInfo<TRoundScoreVo> queryPageList(TRoundScoreBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TRoundScore> lqw = buildQueryWrapper(bo);
        Page<TRoundScoreVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的轮次打分结果列表
     *
     * @param bo 查询条件
     * @return 轮次打分结果列表
     */
    @Override
    public List<TRoundScoreVo> queryList(TRoundScoreBo bo) {
        LambdaQueryWrapper<TRoundScore> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TRoundScore> buildQueryWrapper(TRoundScoreBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TRoundScore> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TRoundScore::getId);
        lqw.eq(bo.getTournamentId() != null, TRoundScore::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getRoundId() != null, TRoundScore::getRoundId, bo.getRoundId());
        lqw.eq(bo.getCompetitorId() != null, TRoundScore::getCompetitorId, bo.getCompetitorId());
        lqw.eq(StringUtils.isNotBlank(bo.getAction()), TRoundScore::getAction, bo.getAction());
        lqw.eq(bo.getRefereeId() != null, TRoundScore::getRefereeId, bo.getRefereeId());
        lqw.eq(bo.getScore() != null, TRoundScore::getScore, bo.getScore());
        lqw.eq(StringUtils.isNotBlank(bo.getDimension()), TRoundScore::getDimension, bo.getDimension());
        return lqw;
    }

    /**
     * 新增轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否新增成功
     */
    @Override
    public Boolean insertByBo(TRoundScoreBo bo) {
        TRoundScore add = MapstructUtils.convert(bo, TRoundScore.class);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否修改成功
     */
    @Override
    public Boolean updateByBo(TRoundScoreBo bo) {
        TRoundScore update = MapstructUtils.convert(bo, TRoundScore.class);
        validEntityBeforeSave(update);
        return baseMapper.updateById(update) > 0;
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TRoundScore entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除轮次打分结果信息
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
