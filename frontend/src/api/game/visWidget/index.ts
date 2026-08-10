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
