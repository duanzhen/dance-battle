package com.dance.street.game.service;

import com.dance.street.game.domain.vo.TVisWidgetVo;
import com.dance.street.game.domain.bo.TVisWidgetBo;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 场景控件元素Service接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface ITVisWidgetService {

    /**
     * 查询场景控件元素
     *
     * @param id 主键
     * @return 场景控件元素
     */
    TVisWidgetVo queryById(Long id);

    /**
     * 按 JSON 补丁回写倒计时组件的计时状态:补丁里的键合并进组件的 dataConfig。
     *
     * <p>大屏(公开播放端,无管理员 JWT)上按「开始/暂停」也要能落库,刷新后按 endAt 续跑,
     * 所以单独开一个窄接口。写入白名单只有 {@code endAt}/{@code remainMs} 两个键
     * (组件配置本身就是 JSON,计划时长 hours/minutes/seconds/milliseconds 仍归组件属性)。</p>
     *
     * @param widgetId 组件ID(必须是 TIMER 组件)
     * @param patch    待合并的 JSON 键值(如 {"endAt":1699999999999,"remainMs":null});缺省键保持原值
     */
    void updateTimerState(Long widgetId, Map<String, Object> patch);

    /**
     * 按 JSON 补丁回写视频组件的播放状态:补丁里的键合并进组件的 dataConfig。
     *
     * <p>白名单只有 {@code videoPlaying}/{@code videoStartedAt}/{@code videoPositionMs} 三个键。
     * 播放/暂停/结束都在管理端画布操作,落库后广播 sceneUpdate,让大屏投射端跟随同一份状态。</p>
     *
     * @param widgetId 组件ID(必须是 VIDEO 组件)
     * @param patch    待合并的 JSON 键值;缺省键保持原值
     */
    void updateVideoState(Long widgetId, Map<String, Object> patch);

    /**
     * 分页查询场景控件元素列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 场景控件元素分页列表
     */
    TableDataInfo<TVisWidgetVo> queryPageList(TVisWidgetBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的场景控件元素列表
     *
     * @param bo 查询条件
     * @return 场景控件元素列表
     */
    List<TVisWidgetVo> queryList(TVisWidgetBo bo);

    /**
     * 新增场景控件元素
     *
     * @param bo 场景控件元素
     * @return 新增后的场景控件元素
     */
    TVisWidgetVo insertByBo(TVisWidgetBo bo);

    /**
     * 修改场景控件元素
     *
     * @param bo 场景控件元素
     * @return 修改后的场景控件元素
     */
    TVisWidgetVo updateByBo(TVisWidgetBo bo);

    /**
     * 校验并批量删除场景控件元素信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 图层排序:上移/下移交换相邻控件 zIndex(按场景加锁原子)
     */
    void moveLayer(Long widgetId, String dir);

    /**
     * 图层批量重排:按 widgetIds 顺序(上→下)重分配 zIndex(加锁原子)
     */
    void reorder(Long sceneId, List<Long> widgetIds);
}
