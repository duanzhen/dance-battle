package com.dance.street.game.service.impl;

import cn.hutool.core.lang.UUID;
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
import com.dance.street.game.domain.bo.TRefereeBo;
import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.service.ITRefereeService;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 裁判Service业务层处理
 *
 * @author duane
 * @date 2026-01-11
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TRefereeServiceImpl implements ITRefereeService {

    private final TRefereeMapper baseMapper;
    private final TRefereeStageMapper refereeStageMapper;

    /**
     * 查询裁判
     *
     * @param id 主键
     * @return 裁判
     */
    @Override
    public TRefereeVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询裁判列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 裁判分页列表
     */
    @Override
    public TableDataInfo<TRefereeVo> queryPageList(TRefereeBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TReferee> lqw = buildQueryWrapper(bo);
        Page<TRefereeVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillAssignedStageCount(result.getRecords(), bo.getTournamentId());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的裁判列表
     *
     * @param bo 查询条件
     * @return 裁判列表
     */
    @Override
    public List<TRefereeVo> queryList(TRefereeBo bo) {
        LambdaQueryWrapper<TReferee> lqw = buildQueryWrapper(bo);
        List<TRefereeVo> list = baseMapper.selectVoList(lqw);
        fillAssignedStageCount(list, bo.getTournamentId());
        return list;
    }

    /**
     * 批量回填裁判已绑定的赛段数量(t_referee_stage 统计)。
     * 前端据此展示状态:绑定过赛段 → ACTIVE,否则 STANDBY。
     */
    private void fillAssignedStageCount(List<TRefereeVo> vos, Long tournamentId) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<Long> refereeIds = vos.stream().map(TRefereeVo::getId).toList();
        var qw = Wrappers.<TRefereeStage>lambdaQuery()
            .in(TRefereeStage::getRefereeId, refereeIds);
        if (tournamentId != null) {
            qw.eq(TRefereeStage::getTournamentId, tournamentId);
        }
        Map<Long, Long> countMap = refereeStageMapper.selectList(qw).stream()
            .collect(Collectors.groupingBy(TRefereeStage::getRefereeId, Collectors.counting()));
        vos.forEach(v -> v.setAssignedStageCount(countMap.getOrDefault(v.getId(), 0L)));
    }

    private LambdaQueryWrapper<TReferee> buildQueryWrapper(TRefereeBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TReferee> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TReferee::getId);
        lqw.eq(bo.getTournamentId() != null, TReferee::getTournamentId, bo.getTournamentId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TReferee::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getPermissions()), TReferee::getPermissions, bo.getPermissions());
        return lqw;
    }

    /**
     * 新增裁判
     *
     * @param bo 裁判
     * @return 是否新增成功
     */
    @Override
    public Boolean insertByBo(TRefereeBo bo) {
        TReferee add = MapstructUtils.convert(bo, TReferee.class);
        add.setAuthKey(UUID.randomUUID().toString(true));
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改裁判
     *
     * @param bo 裁判
     * @return 是否修改成功
     */
    @Override
    public Boolean updateByBo(TRefereeBo bo) {
        TReferee update = MapstructUtils.convert(bo, TReferee.class);
        validEntityBeforeSave(update);
        return baseMapper.updateById(update) > 0;
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TReferee entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除裁判信息
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

    /**
     * 获取裁判登录凭证
     *
     * @param id 主键
     * @return 登录凭证
     */
    @Override
    public String getAuthKeyById(Long id) {
        TReferee referee = baseMapper.selectById(id);
        return referee != null ? referee.getAuthKey() : null;
    }

    /**
     * 生成新的裁判登录凭证
     *
     * @param id 主键
     * @return 新的登录凭证
     */
    @Override
    public String regenerateAuthKey(Long id) {
        TReferee referee = baseMapper.selectById(id);
        if (referee == null) {
            return null;
        }
        String newAuthKey = UUID.randomUUID().toString(true);
        referee.setAuthKey(newAuthKey);
        baseMapper.updateById(referee);
        return newAuthKey;
    }

    /**
     * 根据authKey查找裁判
     *
     * @param authKey 登录凭证
     * @return 裁判信息
     */
    @Override
    public TRefereeVo findByAuthKey(String authKey) {
        TReferee referee = baseMapper.selectOne(
            Wrappers.<TReferee>lambdaQuery().eq(TReferee::getAuthKey, authKey));
        if (referee == null) {
            return null;
        }
        return baseMapper.selectVoById(referee.getId());
    }
}
