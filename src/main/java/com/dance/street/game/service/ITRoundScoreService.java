package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TRoundScoreVo;
import com.dance.street.game.domain.bo.TRoundScoreBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 轮次打分结果Service接口
 *
 * @author duane
 * @date 2026-01-11
 */
public interface ITRoundScoreService {

    /**
     * 查询轮次打分结果
     *
     * @param id 主键
     * @return 轮次打分结果
     */
    TRoundScoreVo queryById(Long id);

    /**
     * 分页查询轮次打分结果列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 轮次打分结果分页列表
     */
    TableDataInfo<TRoundScoreVo> queryPageList(TRoundScoreBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的轮次打分结果列表
     *
     * @param bo 查询条件
     * @return 轮次打分结果列表
     */
    List<TRoundScoreVo> queryList(TRoundScoreBo bo);

    /**
     * 新增轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否新增成功
     */
    Boolean insertByBo(TRoundScoreBo bo);

    /**
     * 修改轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否修改成功
     */
    Boolean updateByBo(TRoundScoreBo bo);

    /**
     * 校验并批量删除轮次打分结果信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);
}
