package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TCompetitorMemberVo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 参赛成员关联Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITCompetitorMemberService {

    /**
     * 查询参赛成员关联
     *
     * @param id 主键
     * @return 参赛成员关联
     */
    TCompetitorMemberVo queryById(Long id);

    /**
     * 分页查询参赛成员关联列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 参赛成员关联分页列表
     */
    TableDataInfo<TCompetitorMemberVo> queryPageList(TCompetitorMemberBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的参赛成员关联列表
     *
     * @param bo 查询条件
     * @return 参赛成员关联列表
     */
    List<TCompetitorMemberVo> queryList(TCompetitorMemberBo bo);

    /**
     * 新增参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 新增后的参赛成员关联
     */
    TCompetitorMemberVo insertByBo(TCompetitorMemberBo bo);

    /**
     * 修改参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 修改后的参赛成员关联
     */
    TCompetitorMemberVo updateByBo(TCompetitorMemberBo bo);

    /**
     * 校验并批量删除参赛成员关联信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
