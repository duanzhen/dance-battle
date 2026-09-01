import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { MatchVO, MatchForm, MatchQuery } from '@/api/game/match/types';

/**
 * 查询比赛场次列表
 * @param query
 * @returns {*}
 */

export const listMatch = (query?: MatchQuery): AxiosPromise<MatchVO[]> => {
  return request({
    url: '/game/match/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询比赛场次详细
 * @param id
 */
export const getMatch = (id: string | number): AxiosPromise<MatchVO> => {
  return request({
    url: '/game/match/' + id,
    method: 'get'
  });
};

/** 查询场次当前标记的上场选手(海选大屏 widget 用);未标记返回 null */
export const getMatchCurrentCompetitor = (id: string | number) => {
  return request({
    url: '/game/match/' + id + '/current-competitor',
    method: 'get'
  });
};

/**
 * 新增比赛场次
 * @param data
 */
export const addMatch = (data: MatchForm) => {
  return request({
    url: '/game/match',
    method: 'post',
    data: data
  });
};

/**
 * 修改比赛场次
 * @param data
 */
export const updateMatch = (data: MatchForm) => {
  return request({
    url: '/game/match',
    method: 'put',
    data: data
  });
};

/**
 * 删除比赛场次
 * @param id
 */
export const delMatch = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/match/' + id,
    method: 'delete'
  });
};
