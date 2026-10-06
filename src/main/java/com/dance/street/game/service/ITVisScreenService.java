package com.dance.street.game.service;

import com.dance.street.game.domain.bo.TVisScreenBo;
import com.dance.street.game.domain.vo.TVisScreenVo;

import java.util.Collection;
import java.util.List;

/**
 * 大屏屏幕配置Service接口
 *
 * @author duane
 */
public interface ITVisScreenService {

    /**
     * 查询某赛事的屏幕列表(按排序)。赛事下没有屏幕时自动补一块默认屏幕,
     * 保证每个控制端加载后至少有一块屏可投射。
     */
    List<TVisScreenVo> listByTournament(Long tournamentId);

    /**
     * 新增屏幕
     */
    TVisScreenVo insertByBo(TVisScreenBo bo);

    /**
     * 修改屏幕(改名 / 排序)
     */
    TVisScreenVo updateByBo(TVisScreenBo bo);

    /**
     * 删除屏幕
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 记录某屏幕当前投射的场景(统一投射入口调用,多控制端据此保持一致)。
     *
     * @param screenId 屏幕ID,不存在时忽略(兼容历史遗留的本地屏幕)
     * @param sceneId  场景ID,为 null 表示清除投射
     */
    void bindCurrentScene(Long screenId, Long sceneId);
}
