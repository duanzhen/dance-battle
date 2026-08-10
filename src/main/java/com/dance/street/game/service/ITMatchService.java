package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TMatchVo;
import com.dance.street.game.domain.bo.TMatchBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 比赛场次Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITMatchService {

    /**
     * 查询比赛场次
     *
     * @param id 主键
     * @return 比赛场次
     */
    TMatchVo queryById(Long id);

    /**
     * 分页查询比赛场次列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 比赛场次分页列表
     */
    TableDataInfo<TMatchVo> queryPageList(TMatchBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的比赛场次列表
     *
     * @param bo 查询条件
     * @return 比赛场次列表
     */
    List<TMatchVo> queryList(TMatchBo bo);

    /**
     * 新增比赛场次
     *
     * @param bo 比赛场次
     * @return 新增后的比赛场次
     */
    TMatchVo insertByBo(TMatchBo bo);

    /**
     * 修改比赛场次
     *
     * @param bo 比赛场次
     * @return 修改后的比赛场次
     */
    TMatchVo updateByBo(TMatchBo bo);

    /**
     * 校验并批量删除比赛场次信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
