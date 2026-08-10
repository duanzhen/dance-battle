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
import com.dance.street.game.domain.bo.TVisSceneBo;
import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.service.ITVisSceneService;
import org.dromara.common.sse.utils.TournamentSseMessageUtils;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 可视化场景配置Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TVisSceneServiceImpl implements ITVisSceneService {

    private final TVisSceneMapper baseMapper;

    /**
     * 查询可视化场景配置
     *
     * @param id 主键
     * @return 可视化场景配置
     */
    @Override
    public TVisSceneVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询可视化场景配置列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 可视化场景配置分页列表
     */
    @Override
    public TableDataInfo<TVisSceneVo> queryPageList(TVisSceneBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TVisScene> lqw = buildQueryWrapper(bo);
        Page<TVisSceneVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的可视化场景配置列表
     *
     * @param bo 查询条件
     * @return 可视化场景配置列表
     */
    @Override
    public List<TVisSceneVo> queryList(TVisSceneBo bo) {
        LambdaQueryWrapper<TVisScene> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TVisScene> buildQueryWrapper(TVisSceneBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TVisScene> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TVisScene::getId);
        lqw.eq(bo.getTournamentId() != null, TVisScene::getTournamentId, bo.getTournamentId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TVisScene::getName, bo.getName());
        lqw.eq(bo.getDesignWidth() != null, TVisScene::getDesignWidth, bo.getDesignWidth());
        lqw.eq(bo.getDesignHeight() != null, TVisScene::getDesignHeight, bo.getDesignHeight());
        return lqw;
    }

    /**
     * 新增可视化场景配置
     *
     * @param bo 可视化场景配置
     * @return 新增后的可视化场景配置
     */
    @Override
    public TVisSceneVo insertByBo(TVisSceneBo bo) {
        TVisScene add = MapstructUtils.convert(bo, TVisScene.class);
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());

        // 通知所有显示该场景的屏幕更新
        notifySceneUpdate(add.getTournamentId(), add.getId());

        return MapstructUtils.convert(add, TVisSceneVo.class);
    }

    /**
     * 修改可视化场景配置
     *
     * @param bo 可视化场景配置
     * @return 修改后的可视化场景配置
     */
    @Override
    public TVisSceneVo updateByBo(TVisSceneBo bo) {
        TVisScene update = MapstructUtils.convert(bo, TVisScene.class);
        validEntityBeforeSave(update);
        baseMapper.updateById(update);

        // 通知所有显示该场景的屏幕更新
        notifySceneUpdate(update.getTournamentId(), update.getId());

        return MapstructUtils.convert(update, TVisSceneVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TVisScene entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除可视化场景配置信息
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

        // 获取要删除的场景所属的赛事ID
        List<TVisScene> scenes = baseMapper.selectBatchIds(ids);
        boolean result = baseMapper.deleteByIds(ids) > 0;

        // 通知所有显示相关场景的屏幕更新
        if (result) {
            scenes.forEach(scene -> notifySceneUpdate(scene.getTournamentId(), scene.getId()));
        }

        return result;
    }

    /**
     * 通知场景更新
     *
     * @param tournamentId 赛事ID
     * @param sceneId      场景ID
     */
    private void notifySceneUpdate(Long tournamentId, Long sceneId) {
        if (tournamentId != null && sceneId != null) {
            TournamentSseMessageUtils.notifySceneUpdate(
                String.valueOf(tournamentId),
                String.valueOf(sceneId)
            );
        }
    }
}
