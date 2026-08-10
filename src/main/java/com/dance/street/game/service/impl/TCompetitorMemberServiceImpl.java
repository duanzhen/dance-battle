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
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.vo.TCompetitorMemberVo;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.service.ITCompetitorMemberService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 参赛成员关联Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TCompetitorMemberServiceImpl implements ITCompetitorMemberService {

    private final TCompetitorMemberMapper baseMapper;

    /**
     * 查询参赛成员关联
     *
     * @param id 主键
     * @return 参赛成员关联
     */
    @Override
    public TCompetitorMemberVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询参赛成员关联列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 参赛成员关联分页列表
     */
    @Override
    public TableDataInfo<TCompetitorMemberVo> queryPageList(TCompetitorMemberBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TCompetitorMember> lqw = buildQueryWrapper(bo);
        Page<TCompetitorMemberVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的参赛成员关联列表
     *
     * @param bo 查询条件
     * @return 参赛成员关联列表
     */
    @Override
    public List<TCompetitorMemberVo> queryList(TCompetitorMemberBo bo) {
        LambdaQueryWrapper<TCompetitorMember> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TCompetitorMember> buildQueryWrapper(TCompetitorMemberBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TCompetitorMember> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TCompetitorMember::getId);
        lqw.eq(bo.getTournamentId() != null, TCompetitorMember::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getCompetitorId() != null, TCompetitorMember::getCompetitorId, bo.getCompetitorId());
        lqw.eq(bo.getPlayerId() != null, TCompetitorMember::getPlayerId, bo.getPlayerId());
        lqw.eq(StringUtils.isNotBlank(bo.getRole()), TCompetitorMember::getRole, bo.getRole());
        return lqw;
    }

    /**
     * 新增参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 新增后的参赛成员关联
     */
    @Override
    public TCompetitorMemberVo insertByBo(TCompetitorMemberBo bo) {
        TCompetitorMember add = MapstructUtils.convert(bo, TCompetitorMember.class);
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TCompetitorMemberVo.class);
    }

    /**
     * 修改参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 修改后的参赛成员关联
     */
    @Override
    public TCompetitorMemberVo updateByBo(TCompetitorMemberBo bo) {
        TCompetitorMember update = MapstructUtils.convert(bo, TCompetitorMember.class);
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TCompetitorMemberVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TCompetitorMember entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除参赛成员关联信息
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
