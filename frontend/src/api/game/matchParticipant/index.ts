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
 * 按赛段一次取回参赛方,并按场次分组(key = matchId 字符串)。
 *
 * 各组件仍只拉自己赛段的数据;这个包装只是把"逐场请求"收成一次请求
 * (16 强按场次请求 = 16 个 HTTP,浏览器并发上限下要排好几轮)。
 */
export const listParticipantsByStage = async (
  stageId: string | number
): Promise<Record<string, MatchParticipantVO[]>> => {
  const grouped: Record<string, MatchParticipantVO[]> = {};
  const res: any = await listMatchParticipant({ stageId, pageNum: 1, pageSize: 999 } as MatchParticipantQuery);
  const rows: MatchParticipantVO[] = res?.data?.data || res?.data || [];
  rows.forEach((p: MatchParticipantVO) => {
    if (p?.matchId == null) return;
    const key = String(p.matchId);
    (grouped[key] ||= []).push(p);
  });
  return grouped;
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
