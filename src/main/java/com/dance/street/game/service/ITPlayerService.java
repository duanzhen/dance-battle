package com.dance.street.game.service;

import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.bo.CheckInBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 选手自然人Service接口
 *
 * @author duane
 * @date 2026-01-11
 */
public interface ITPlayerService {

    /**
     * 查询选手自然人
     *
     * @param id 主键
     * @return 选手自然人
     */
    TPlayerVo queryById(Long id);

    /**
     * 分页查询选手自然人列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 选手自然人分页列表
     */
    TableDataInfo<TPlayerVo> queryPageList(TPlayerBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的选手自然人列表
     *
     * @param bo 查询条件
     * @return 选手自然人列表
     */
    List<TPlayerVo> queryList(TPlayerBo bo);

    /**
     * 新增选手自然人
     *
     * @param bo 选手自然人
     * @return 新增后的选手自然人
     */
    TPlayerVo insertByBo(TPlayerBo bo);

    /**
     * 修改选手自然人
     *
     * @param bo 选手自然人
     * @return 修改后的选手自然人
     */
    TPlayerVo updateByBo(TPlayerBo bo);

    /**
     * 校验并批量删除选手自然人信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 选手签到
     *
     * @param bo 签到请求
     * @return 签到后的选手信息
     */
    TPlayerVo checkIn(CheckInBo bo);

    /**
     * 批量导入选手
     *
     * @param list         导入数据
     * @param tournamentId 赛事ID
     * @return 成功导入数量
     */
    int importPlayers(List<PlayerImportVo> list, Long tournamentId);
}
