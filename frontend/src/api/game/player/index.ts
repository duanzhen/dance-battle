import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { PlayerVO, PlayerForm, PlayerQuery } from '@/api/game/player/types';

/**
 * 查询选手自然人列表
 * @param query
 * @returns {*}
 */

export const listPlayer = (query?: PlayerQuery): AxiosPromise<PlayerVO[]> => {
  return request({
    url: '/game/player/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询选手自然人详细
 * @param id
 */
export const getPlayer = (id: string | number): AxiosPromise<PlayerVO> => {
  return request({
    url: '/game/player/' + id,
    method: 'get'
  });
};

/**
 * 新增选手自然人
 * @param data
 */
export const addPlayer = (data: PlayerForm) => {
  return request({
    url: '/game/player',
    method: 'post',
    data: data
  });
};

/**
 * 修改选手自然人
 * @param data
 */
export const updatePlayer = (data: PlayerForm) => {
  return request({
    url: '/game/player',
    method: 'put',
    data: data
  });
};

/**
 * 删除选手自然人
 * @param id
 */
export const delPlayer = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/player/' + id,
    method: 'delete'
  });
};

/**
 * 选手签到
 * @param data
 */
export const checkInPlayer = (data: {
  playerId: string | number;
  checkInType: 'CREATE' | 'JOIN';
  competitorNumber: string;
  competitorId?: string | number;
  name?: string;
  avatar?: string;
}) => {
  return request({
    url: '/game/player/checkin',
    method: 'post',
    data: data
  });
};

/**
 * 批量导入选手
 */
export const importPlayers = (data: FormData) => {
  return request({
    url: '/game/player/import',
    method: 'post',
    data: data,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
};
