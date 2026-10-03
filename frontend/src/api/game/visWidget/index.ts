import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { VisWidgetVO, VisWidgetForm, VisWidgetQuery } from '@/api/game/visWidget/types';

/**
 * 查询场景控件元素列表
 * @param query
 * @returns {*}
 */

export const listVisWidget = (query?: VisWidgetQuery): AxiosPromise<VisWidgetVO[]> => {
  return request({
    url: '/game/visWidget/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询场景控件元素详细
 * @param id
 */
export const getVisWidget = (id: string | number): AxiosPromise<VisWidgetVO> => {
  return request({
    url: '/game/visWidget/' + id,
    method: 'get'
  });
};

/**
 * 图层排序:上移/下移交换相邻控件 zIndex(后端加锁原子)
 */
export const moveWidgetLayer = (id: string | number, dir: 'up' | 'down') => {
  return request({
    url: '/game/visWidget/' + id + '/layer',
    method: 'put',
    params: { dir }
  });
};

/**
 * 图层批量重排(拖动排序):按 widgetIds 顺序重分配 zIndex
 */
export const reorderWidgets = (sceneId: string | number, widgetIds: (string | number)[]) => {
  return request({
    url: '/game/visWidget/reorder',
    method: 'post',
    data: { sceneId, widgetIds }
  });
};

/**
 * 新增场景控件元素
 * @param data
 */
export const addVisWidget = (data: VisWidgetForm) => {
  return request({
    url: '/game/visWidget',
    method: 'post',
    data: data
  });
};

/**
 * 修改场景控件元素
 * @param data
 */
export const updateVisWidget = (data: VisWidgetForm) => {
  return request({
    url: '/game/visWidget',
    method: 'put',
    data: data,
    headers: {
      repeatSubmit: true
    }
  });
};

/**
 * 删除场景控件元素
 * @param id
 */
export const delVisWidget = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/visWidget/' + id,
    method: 'delete'
  });
};

/**
 * 倒计时开始/暂停(管理端):只写 dataConfig 的 endAt/remainMs,锁定控件也允许。
 * 大屏投射端只读,计时控制走这里(需要管理端登录态)。
 */
export const updateWidgetTimerState = (
  id: string | number,
  data: { endAt?: number | null; remainMs?: number | null }
): AxiosPromise<void> => {
  return request({
    url: `/game/visWidget/${id}/timer-state`,
    method: 'post',
    data
  });
};

/**
 * 视频播放/暂停/结束(管理端):只写 dataConfig 的
 * videoPlaying / videoStartedAt / videoPositionMs,落库后广播让大屏跟着同步。
 */
export const updateWidgetVideoState = (
  id: string | number,
  data: { videoPlaying?: boolean; videoStartedAt?: number | null; videoPositionMs?: number | null }
): AxiosPromise<void> => {
  return request({
    url: `/game/visWidget/${id}/video-state`,
    method: 'post',
    data
  });
};
