package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TMatchRoundVo;
import com.dance.street.game.domain.bo.TMatchRoundBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 比赛轮次Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITMatchRoundService {

    /**
     * 查询比赛轮次
     *
     * @param id 主键
     * @return 比赛轮次
     */
    TMatchRoundVo queryById(Long id);

    /**
     * 分页查询比赛轮次列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 比赛轮次分页列表
     */
    TableDataInfo<TMatchRoundVo> queryPageList(TMatchRoundBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的比赛轮次列表
     *
     * @param bo 查询条件
     * @return 比赛轮次列表
     */
    List<TMatchRoundVo> queryList(TMatchRoundBo bo);

    /**
     * 新增比赛轮次
     *
     * @param bo 比赛轮次
     * @return 新增后的比赛轮次
     */
    TMatchRoundVo insertByBo(TMatchRoundBo bo);

    /**
     * 修改比赛轮次
     *
     * @param bo 比赛轮次
     * @return 修改后的比赛轮次
     */
    TMatchRoundVo updateByBo(TMatchRoundBo bo);

    /**
     * 校验并批量删除比赛轮次信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
