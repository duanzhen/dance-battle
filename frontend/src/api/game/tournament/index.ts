import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { TournamentVO, TournamentForm, TournamentQuery } from '@/api/game/tournament/types';

/**
 * 查询赛事主列表
 * @param query
 * @returns {*}
 */

export const listTournament = (query?: TournamentQuery): AxiosPromise<TournamentVO[]> => {
  return request({
    url: '/game/tournament/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询赛事主详细
 * @param id
 */
export const getTournament = (id: string | number): AxiosPromise<TournamentVO> => {
  return request({
    url: '/game/tournament/' + id,
    method: 'get'
  });
};

/**
 * 新增赛事主
 * @param data
 */
export const addTournament = (data: TournamentForm) => {
  return request({
    url: '/game/tournament',
    method: 'post',
    data: data
  });
};

/**
 * 按模版创建赛事:自动创建赛事 + 赛段链 + 场景 + 对战树关联
 * @param data 赛事名称 + 模版编码
 */
export const createTournamentByTemplate = (data: {
  name: string;
  templateCode: string;
  remark?: string;
  refereeCount?: number;
  refereeNames?: string[];
}) => {
  return request({
    url: '/game/tournament/create-by-template',
    method: 'post',
    data: data
  });
};

/**
 * 修改赛事主
 * @param data
 */
export const updateTournament = (data: TournamentForm) => {
  return request({
    url: '/game/tournament',
    method: 'put',
    data: data
  });
};

/**
 * 删除赛事主
 * @param id
 */
export const delTournament = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/tournament/' + id,
    method: 'delete'
  });
};

/**
 * 获取赛事登录凭证(手机导播台 auth_key)
 * @param id 赛事ID
 */
export const getTournamentAuthKey = (id: string | number) => {
  return request({
    url: '/game/tournament/authKey/' + id,
    method: 'get'
  });
};

/**
 * 重置赛事登录凭证(泄露后调用,旧凭证立即失效)
 * @param id 赛事ID
 */
export const regenerateTournamentAuthKey = (id: string | number) => {
  return request({
    url: '/game/tournament/authKey/' + id,
    method: 'put'
  });
};
