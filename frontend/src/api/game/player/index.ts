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
  /** 目标圈场次ID(海选/排名赛分圈、线下抽签指定圈时传;不传由系统自动择优) */
  matchId?: string | number;
}) => {
  return request({
    url: '/game/player/checkin',
    method: 'post',
    data: data
  });
};

/**
 * 编辑签到结果(改号码/换圈/改名/头像)
 * @param data
 */
export const editCheckIn = (data: {
  playerId: string | number;
  competitorNumber?: string;
  /** 目标圈场次ID(随机分圈手动换圈时传;按号分圈由号码自动决定圈位) */
  matchId?: string | number;
  name?: string;
  avatar?: string;
}) => {
  return request({
    url: '/game/player/checkin',
    method: 'put',
    data: data
  });
};

/**
 * 解除签到(回到未签到状态)
 * @param playerId
 */
export const cancelCheckIn = (playerId: string | number) => {
  return request({
    url: '/game/player/checkin/' + playerId,
    method: 'delete'
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
