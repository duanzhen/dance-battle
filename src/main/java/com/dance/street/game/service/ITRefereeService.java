package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TRefereeVo;
import com.dance.street.game.domain.bo.TRefereeBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * 裁判Service接口
 *
 * @author duane
 * @date 2026-01-11
 */
public interface ITRefereeService {

    /**
     * 查询裁判
     *
     * @param id 主键
     * @return 裁判
     */
    TRefereeVo queryById(Long id);

    /**
     * 分页查询裁判列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 裁判分页列表
     */
    TableDataInfo<TRefereeVo> queryPageList(TRefereeBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的裁判列表
     *
     * @param bo 查询条件
     * @return 裁判列表
     */
    List<TRefereeVo> queryList(TRefereeBo bo);

    /**
     * 新增裁判
     *
     * @param bo 裁判
     * @return 是否新增成功
     */
    Boolean insertByBo(TRefereeBo bo);

    /**
     * 修改裁判
     *
     * @param bo 裁判
     * @return 是否修改成功
     */
    Boolean updateByBo(TRefereeBo bo);

    /**
     * 校验并批量删除裁判信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 获取裁判登录凭证
     *
     * @param id 主键
     * @return 登录凭证
     */
    String getAuthKeyById(Long id);

    /**
     * 生成新的裁判登录凭证
     *
     * @param id 主键
     * @return 新的登录凭证
     */
    String regenerateAuthKey(Long id);

    /**
     * 根据authKey查找裁判
     *
     * @param authKey 登录凭证
     * @return 裁判信息
     */
    TRefereeVo findByAuthKey(String authKey);
}
