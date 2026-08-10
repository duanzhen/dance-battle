import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { RefereeVO, RefereeForm, RefereeQuery } from '@/api/game/referee/types';

/**
 * 查询裁判列表
 * @param query
 * @returns {*}
 */
export const listReferee = (query?: RefereeQuery): AxiosPromise<RefereeVO[]> => {
  return request({
    url: '/game/referee/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询裁判详细
 * @param id
 */
export const getReferee = (id: string | number): AxiosPromise<RefereeVO> => {
  return request({
    url: '/game/referee/' + id,
    method: 'get'
  });
};

/**
 * 新增裁判
 * @param data
 */
export const addReferee = (data: RefereeForm) => {
  return request({
    url: '/game/referee',
    method: 'post',
    data: data
  });
};

/**
 * 修改裁判
 * @param data
 */
export const updateReferee = (data: RefereeForm) => {
  return request({
    url: '/game/referee',
    method: 'put',
    data: data
  });
};

/**
 * 删除裁判
 * @param id
 */
export const delReferee = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/referee/' + id,
    method: 'delete'
  });
};

/**
 * 获取裁判认证密钥
 * @param id
 */
export const getRefereeAuthKey = (id: string | number) => {
  return request({
    url: '/game/referee/authKey/' + id,
    method: 'get'
  });
};

/**
 * 刷新裁判认证密钥
 * @param id
 */
export const regenerateRefereeAuthKey = (id: string | number) => {
  return request({
    url: '/game/referee/authKey/' + id,
    method: 'put'
  });
};
