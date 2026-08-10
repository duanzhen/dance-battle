package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 参赛单位Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITCompetitorService {

    /**
     * 查询参赛单位
     *
     * @param id 主键
     * @return 参赛单位
     */
    TCompetitorVo queryById(Long id);

    /**
     * 分页查询参赛单位列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 参赛单位分页列表
     */
    TableDataInfo<TCompetitorVo> queryPageList(TCompetitorBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的参赛单位列表
     *
     * @param bo 查询条件
     * @return 参赛单位列表
     */
    List<TCompetitorVo> queryList(TCompetitorBo bo);

    /**
     * 新增参赛单位
     *
     * @param bo 参赛单位
     * @return 新增后的参赛单位
     */
    TCompetitorVo insertByBo(TCompetitorBo bo);

    /**
     * 修改参赛单位
     *
     * @param bo 参赛单位
     * @return 修改后的参赛单位
     */
    TCompetitorVo updateByBo(TCompetitorBo bo);

    /**
     * 校验并批量删除参赛单位信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 根据ID集合批量查询参赛单位
     *
     * @param ids ID集合
     * @return 参赛单位列表
     */
    List<TCompetitorVo> listByIds(Collection<Long> ids);
}
