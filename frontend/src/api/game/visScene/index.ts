import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { VisSceneVO, VisSceneForm, VisSceneQuery } from '@/api/game/visScene/types';

/**
 * 查询可视化场景配置列表
 * @param query
 * @returns {*}
 */

export const listVisScene = (query?: VisSceneQuery): AxiosPromise<VisSceneVO[]> => {
  return request({
    url: '/game/visScene/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询可视化场景配置详细
 * @param id
 */
export const getVisScene = (id: string | number): AxiosPromise<VisSceneVO> => {
  return request({
    url: '/game/visScene/' + id,
    method: 'get'
  });
};

/**
 * 新增可视化场景配置
 * @param data
 */
export const addVisScene = (data: VisSceneForm) => {
  return request({
    url: '/game/visScene',
    method: 'post',
    data: data
  });
};

/**
 * 修改可视化场景配置
 * @param data
 */
export const updateVisScene = (data: VisSceneForm) => {
  return request({
    url: '/game/visScene',
    method: 'put',
    data: data
  });
};

/**
 * 删除可视化场景配置
 * @param id
 */
export const delVisScene = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/visScene/' + id,
    method: 'delete'
  });
};
