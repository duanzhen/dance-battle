import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { MatchParticipantVO, MatchParticipantForm, MatchParticipantQuery } from '@/api/game/matchParticipant/types';

/**
 * 查询场次参赛人员记录列表
 * @param query
 * @returns {*}
 */

export const listMatchParticipant = (query?: MatchParticipantQuery): AxiosPromise<MatchParticipantVO[]> => {
  return request({
    url: '/game/matchParticipant/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询场次参赛人员记录详细
 * @param id
 */
export const getMatchParticipant = (id: string | number): AxiosPromise<MatchParticipantVO> => {
  return request({
    url: '/game/matchParticipant/' + id,
    method: 'get'
  });
};

/**
 * 新增场次参赛人员记录
 * @param data
 */
export const addMatchParticipant = (data: MatchParticipantForm) => {
  return request({
    url: '/game/matchParticipant',
    method: 'post',
    data: data
  });
};

/**
 * 修改场次参赛人员记录
 * @param data
 */
export const updateMatchParticipant = (data: MatchParticipantForm) => {
  return request({
    url: '/game/matchParticipant',
    method: 'put',
    data: data
  });
};

/**
 * 删除场次参赛人员记录
 * @param id
 */
export const delMatchParticipant = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/matchParticipant/' + id,
    method: 'delete'
  });
};
