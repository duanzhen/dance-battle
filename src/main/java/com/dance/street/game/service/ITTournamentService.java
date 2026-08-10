package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 赛事主Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITTournamentService {

    /**
     * 查询赛事主
     *
     * @param id 主键
     * @return 赛事主
     */
    TTournamentVo queryById(Long id);

    /**
     * 分页查询赛事主列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 赛事主分页列表
     */
    TableDataInfo<TTournamentVo> queryPageList(TTournamentBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的赛事主列表
     *
     * @param bo 查询条件
     * @return 赛事主列表
     */
    List<TTournamentVo> queryList(TTournamentBo bo);

    /**
     * 新增赛事主
     *
     * @param bo 赛事主
     * @return 新增后的赛事主
     */
    TTournamentVo insertByBo(TTournamentBo bo);

    /**
     * 按模版创建赛事:自动创建赛事 + 赛段链 + 场景(主视觉/对战) + 对战树 widget 关联
     *
     * @param bo 模版创建参数(赛事名称 + 模版编码)
     * @return 创建后的赛事
     */
    TTournamentVo createByTemplate(TTournamentTemplateBo bo);

    /**
     * 修改赛事主
     *
     * @param bo 赛事主
     * @return 修改后的赛事主
     */
    TTournamentVo updateByBo(TTournamentBo bo);

    /**
     * 校验并批量删除赛事主信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 获取赛事登录凭证
     *
     * @param id 主键
     * @return 登录凭证
     */
    String getAuthKeyById(Long id);

    /**
     * 生成新的赛事登录凭证
     *
     * @param id 主键
     * @return 新的登录凭证
     */
    String regenerateAuthKey(Long id);

    /**
     * 根据赛事登录凭证查找赛事
     *
     * @param authKey 登录凭证
     * @return 赛事信息;凭证无效返回 null
     */
    TTournamentVo findByAuthKey(String authKey);
}
