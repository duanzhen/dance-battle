import request from '@/utils/request';

/**
 * 分配某场次(圈)对应的裁判(全量替换,一圈可多个裁判)
 */
export const assignMatchReferees = (data: { matchId: string | number; tournamentId: string | number; refereeIds: (string | number)[] }) => {
  return request({
    url: '/game/match-referee/assign',
    method: 'post',
    data
  });
};

/**
 * 查询某赛段各场次(圈)已分配的裁判
 */
export const listMatchReferee = (stageId: string | number) => {
  return request({
    url: '/game/match-referee/list',
    method: 'get',
    params: { stageId }
  });
};
