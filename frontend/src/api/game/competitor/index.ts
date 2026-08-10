import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { CompetitorVO, CompetitorForm, CompetitorQuery } from '@/api/game/competitor/types';

/**
 * 查询参赛单位列表
 * @param query
 * @returns {*}
 */

export const listCompetitor = (query?: CompetitorQuery): AxiosPromise<CompetitorVO[]> => {
  return request({
    url: '/game/competitor/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询参赛单位详细
 * @param id
 */
export const getCompetitor = (id: string | number): AxiosPromise<CompetitorVO> => {
  return request({
    url: '/game/competitor/' + id,
    method: 'get'
  });
};

/**
 * 新增参赛单位
 * @param data
 */
export const addCompetitor = (data: CompetitorForm) => {
  return request({
    url: '/game/competitor',
    method: 'post',
    data: data
  });
};

/**
 * 修改参赛单位
 * @param data
 */
export const updateCompetitor = (data: CompetitorForm) => {
  return request({
    url: '/game/competitor',
    method: 'put',
    data: data
  });
};

/**
 * 删除参赛单位
 * @param id
 */
export const delCompetitor = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/competitor/' + id,
    method: 'delete'
  });
};
