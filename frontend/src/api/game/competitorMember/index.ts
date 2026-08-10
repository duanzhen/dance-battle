import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { CompetitorMemberVO, CompetitorMemberForm, CompetitorMemberQuery } from '@/api/game/competitorMember/types';

/**
 * 查询参赛成员关联列表
 * @param query
 * @returns {*}
 */

export const listCompetitorMember = (query?: CompetitorMemberQuery): AxiosPromise<CompetitorMemberVO[]> => {
  return request({
    url: '/game/competitorMember/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询参赛成员关联详细
 * @param id
 */
export const getCompetitorMember = (id: string | number): AxiosPromise<CompetitorMemberVO> => {
  return request({
    url: '/game/competitorMember/' + id,
    method: 'get'
  });
};

/**
 * 新增参赛成员关联
 * @param data
 */
export const addCompetitorMember = (data: CompetitorMemberForm) => {
  return request({
    url: '/game/competitorMember',
    method: 'post',
    data: data
  });
};

/**
 * 修改参赛成员关联
 * @param data
 */
export const updateCompetitorMember = (data: CompetitorMemberForm) => {
  return request({
    url: '/game/competitorMember',
    method: 'put',
    data: data
  });
};

/**
 * 删除参赛成员关联
 * @param id
 */
export const delCompetitorMember = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/competitorMember/' + id,
    method: 'delete'
  });
};
