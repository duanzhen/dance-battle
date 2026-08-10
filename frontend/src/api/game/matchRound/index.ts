import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { MatchRoundVO, MatchRoundForm, MatchRoundQuery } from '@/api/game/matchRound/types';

/**
 * 查询比赛轮次列表
 * @param query
 * @returns {*}
 */

export const listMatchRound = (query?: MatchRoundQuery): AxiosPromise<MatchRoundVO[]> => {
  return request({
    url: '/game/matchRound/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询比赛轮次详细
 * @param id
 */
export const getMatchRound = (id: string | number): AxiosPromise<MatchRoundVO> => {
  return request({
    url: '/game/matchRound/' + id,
    method: 'get'
  });
};

/**
 * 新增比赛轮次
 * @param data
 */
export const addMatchRound = (data: MatchRoundForm) => {
  return request({
    url: '/game/matchRound',
    method: 'post',
    data: data
  });
};

/**
 * 修改比赛轮次
 * @param data
 */
export const updateMatchRound = (data: MatchRoundForm) => {
  return request({
    url: '/game/matchRound',
    method: 'put',
    data: data
  });
};

/**
 * 删除比赛轮次
 * @param id
 */
export const delMatchRound = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/matchRound/' + id,
    method: 'delete'
  });
};
