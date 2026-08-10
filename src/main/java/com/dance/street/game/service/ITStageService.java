package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.bo.TStageBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 赛段流程Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITStageService {

    /**
     * 查询赛段流程
     *
     * @param id 主键
     * @return 赛段流程
     */
    TStageVo queryById(Long id);

    /**
     * 分页查询赛段流程列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 赛段流程分页列表
     */
    TableDataInfo<TStageVo> queryPageList(TStageBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的赛段流程列表
     *
     * @param bo 查询条件
     * @return 赛段流程列表
     */
    List<TStageVo> queryList(TStageBo bo);

    /**
     * 新增赛段流程
     *
     * @param bo 赛段流程
     * @return 新增后的赛段流程
     */
    TStageVo insertByBo(TStageBo bo);

    /**
     * 修改赛段流程
     *
     * @param bo 赛段流程
     * @return 修改后的赛段流程
     */
    TStageVo updateByBo(TStageBo bo);

    /**
     * 校验并批量删除赛段流程信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 根据比赛ID获取第一个赛段
     *
     * @param tournamentId 比赛ID
     * @return 第一个赛段
     */
    TStageVo getFirstStageByTournamentId(Long tournamentId);

    /**
     * 大屏赛程流转:赛事全部赛段链 + 当前进行中赛段/场次
     */
    StageFlowVo getFlowByTournamentId(Long tournamentId);

    /**
     * 下一赛段对战树预排:上一赛段胜者按种子顺位排入本赛段
     */
    PreBracketVo getPreBracket(Long stageId);
}
