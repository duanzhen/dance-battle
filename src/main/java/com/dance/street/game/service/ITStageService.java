package com.dance.street.game.service;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
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
     * 修改赛段流程(只改配置,不动赛段链)
     *
     * <p>客户端提交的 prev/next 一律忽略:它们是客户端的展示副本,过期写回会把链写歪。
     * 调整链顺序用 {@link #moveStageAfter(Long, Long)}。新代码请优先用
     * {@link #updateConfig(TStageConfigBo)}——那个入口的 BO 里根本没有指针字段。</p>
     *
     * @param bo 赛段流程
     * @return 修改后的赛段流程
     */
    TStageVo updateByBo(TStageBo bo);

    /**
     * 修改赛段配置(配置面板入口,不含链表指针)
     *
     * <p>只写 BO 上显式提供的配置列,不读也不写 prev/next,因此纯配置保存
     * 不会产生任何链写入、也不会触发下游名单对账。</p>
     *
     * @param bo 赛段配置
     * @return 修改后的赛段流程(库中整行,含链推导出的 prev/next 展示值)
     */
    TStageVo updateConfig(TStageConfigBo bo);

    /**
     * 调整赛段链顺序:把 {@code stageId} 移到 {@code afterStageId} 之后(空 = 移到链头)。
     *
     * <p>改链的唯一入口:只表达意图,顺序由后端按现有链推导后统一写入。</p>
     *
     * @param stageId      要移动的赛段
     * @param afterStageId 移动到该赛段之后;空 = 移到链头
     */
    void moveStageAfter(Long stageId, Long afterStageId);

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
     * 上一赛段:优先按 prevStageId 直取;prevStageId 悬空(指向已删除赛段)或缺失时,
     * 按同赛事内 nextStageId == 本赛段反向反查兜底(排除 DISCARD),保证断链可自愈。
     *
     * <p>赛段链的遍历口径只此一份:开赛守卫、流程展示等所有调用方共用。</p>
     */
    TStage resolvePrevStage(TStage stage);

    /**
     * 下一赛段对战树预排:上一赛段胜者按种子顺位排入本赛段
     */
    PreBracketVo getPreBracket(Long stageId);
}
