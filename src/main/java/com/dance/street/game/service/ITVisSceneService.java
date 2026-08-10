package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.bo.TVisSceneBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 可视化场景配置Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITVisSceneService {

    /**
     * 查询可视化场景配置
     *
     * @param id 主键
     * @return 可视化场景配置
     */
    TVisSceneVo queryById(Long id);

    /**
     * 分页查询可视化场景配置列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 可视化场景配置分页列表
     */
    TableDataInfo<TVisSceneVo> queryPageList(TVisSceneBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的可视化场景配置列表
     *
     * @param bo 查询条件
     * @return 可视化场景配置列表
     */
    List<TVisSceneVo> queryList(TVisSceneBo bo);

    /**
     * 新增可视化场景配置
     *
     * @param bo 可视化场景配置
     * @return 新增后的可视化场景配置
     */
    TVisSceneVo insertByBo(TVisSceneBo bo);

    /**
     * 修改可视化场景配置
     *
     * @param bo 可视化场景配置
     * @return 修改后的可视化场景配置
     */
    TVisSceneVo updateByBo(TVisSceneBo bo);

    /**
     * 校验并批量删除可视化场景配置信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
