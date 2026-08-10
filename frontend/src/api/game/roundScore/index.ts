import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { RoundScoreVO, RoundScoreForm, RoundScoreQuery } from '@/api/game/roundScore/types';

/**
 * 查询轮次打分列表
 * @param query
 * @returns {*}
 */
export const listRoundScore = (query?: RoundScoreQuery): AxiosPromise<RoundScoreVO[]> => {
  return request({
    url: '/game/roundScore/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询轮次打分详细
 * @param id
 */
export const getRoundScore = (id: string | number): AxiosPromise<RoundScoreVO> => {
  return request({
    url: '/game/roundScore/' + id,
    method: 'get'
  });
};

/**
 * 新增轮次打分
 * @param data
 */
export const addRoundScore = (data: RoundScoreForm) => {
  return request({
    url: '/game/roundScore',
    method: 'post',
    data: data
  });
};

/**
 * 修改轮次打分
 * @param data
 */
export const updateRoundScore = (data: RoundScoreForm) => {
  return request({
    url: '/game/roundScore',
    method: 'put',
    data: data
  });
};

/**
 * 删除轮次打分
 * @param id
 */
export const delRoundScore = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/roundScore/' + id,
    method: 'delete'
  });
};
