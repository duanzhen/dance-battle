import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { VisScreenVO, VisScreenForm, VisScreenQuery } from '@/api/game/visScreen/types';

/**
 * 查询某赛事的屏幕列表(服务端共用,空赛事会自动补一块默认屏)
 */
export const listVisScreen = (query: VisScreenQuery): AxiosPromise<VisScreenVO[]> => {
  return request({
    url: '/game/visScreen/list',
    method: 'get',
    params: query
  });
};

/**
 * 新增屏幕
 */
export const addVisScreen = (data: VisScreenForm) => {
  return request({
    url: '/game/visScreen',
    method: 'post',
    data: data
  });
};

/**
 * 修改屏幕(改名 / 排序)
 */
export const updateVisScreen = (data: VisScreenForm) => {
  return request({
    url: '/game/visScreen',
    method: 'put',
    data: data
  });
};

/**
 * 删除屏幕
 */
export const delVisScreen = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/visScreen/' + id,
    method: 'delete'
  });
};
