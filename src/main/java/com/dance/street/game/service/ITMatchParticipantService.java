package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TMatchParticipantVo;
import com.dance.street.game.domain.bo.TMatchParticipantBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 场次参赛人员记录Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITMatchParticipantService {

    /**
     * 查询场次参赛人员记录
     *
     * @param id 主键
     * @return 场次参赛人员记录
     */
    TMatchParticipantVo queryById(Long id);

    /**
     * 分页查询场次参赛人员记录列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 场次参赛人员记录分页列表
     */
    TableDataInfo<TMatchParticipantVo> queryPageList(TMatchParticipantBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的场次参赛人员记录列表
     *
     * @param bo 查询条件
     * @return 场次参赛人员记录列表
     */
    List<TMatchParticipantVo> queryList(TMatchParticipantBo bo);

    /**
     * 新增场次参赛人员记录
     *
     * @param bo 场次参赛人员记录
     * @return 新增后的场次参赛人员记录
     */
    TMatchParticipantVo insertByBo(TMatchParticipantBo bo);

    /**
     * 修改场次参赛人员记录
     *
     * @param bo 场次参赛人员记录
     * @return 修改后的场次参赛人员记录
     */
    TMatchParticipantVo updateByBo(TMatchParticipantBo bo);

    /**
     * 校验并批量删除场次参赛人员记录信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
