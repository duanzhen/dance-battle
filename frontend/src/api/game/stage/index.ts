import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { StageVO, StageForm, StageQuery } from '@/api/game/stage/types';

/**
 * 查询赛段流程列表
 * @param query
 * @returns {*}
 */

export const listStage = (query?: StageQuery): AxiosPromise<StageVO[]> => {
  return request({
    url: '/game/stage/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询赛段流程详细
 * @param id
 */
export const getStage = (id: string | number): AxiosPromise<StageVO> => {
  return request({
    url: '/game/stage/' + id,
    method: 'get'
  });
};

/**
 * 新增赛段流程
 * @param data
 */
export const addStage = (data: StageForm) => {
  return request({
    url: '/game/stage',
    method: 'post',
    data: data
  });
};

/**
 * 修改赛段流程
 * @param data
 */
export const updateStage = (data: StageForm) => {
  return request({
    url: '/game/stage',
    method: 'put',
    data: data
  });
};

/**
 * 删除赛段流程
 * @param id
 */
export const delStage = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/stage/' + id,
    method: 'delete'
  });
};

/**
 * 获取赛事第一个赛段
 * @param tournamentId 赛事ID
 */
export const getFirstStage = (tournamentId: string | number): AxiosPromise<StageVO> => {
  return request({
    url: '/game/stage/first/' + tournamentId,
    method: 'get'
  });
};

/**
 * 大屏赛程流转:赛事全部赛段链 + 当前进行中赛段/场次
 * @param tournamentId 赛事ID
 */
export const getStageFlow = (tournamentId: string | number) => {
  return request({
    url: '/game/stage/flow/' + tournamentId,
    method: 'get'
  });
};

/** 擂台赛总览:轮转队列(含每人积分)与当前对决 */
export const getArenaOverview = (stageId: string | number) => {
  return request({
    url: '/game/stage/' + stageId + '/arena-overview',
    method: 'get'
  });
};

/**
 * 嘉宾加入赛段(除海选外任意赛段,赛段中间态 PENDING/GAMING 可加)
 * @param stageId 赛段ID
 * @param data { name, type?, number?, playerId? }
 */
export const addStageGuest = (stageId: string | number, data: {
  name: string;
  type?: number;
  number?: string;
  playerId?: string | number;
}) => {
  return request({
    url: '/game/stage/' + stageId + '/guest',
    method: 'post',
    data
  });
};

/**
 * 下一赛段对战树预排:上一赛段胜者(含未最终确认)按种子顺位排入本赛段
 * @param stageId 要预排的(下一)赛段ID
 */
export const getStagePreBracket = (stageId: string | number) => {
  return request({
    url: '/game/stage/prebracket/' + stageId,
    method: 'get'
  });
};
