import request from '@/utils/request';

/**
 * 查询某赛段已分配的裁判ID列表
 */
export function getStageRefereeIds(stageId: string | number) {
  return request({
    url: '/game/referee-stage/referee-ids',
    method: 'get',
    params: { stageId }
  });
}

/**
 * 批量设置赛段裁判（全量替换）
 */
export function assignStageReferees(data: { stageId: string | number; tournamentId: string | number; refereeIds: (string | number)[] }) {
  return request({
    url: '/game/referee-stage/assign',
    method: 'post',
    data
  });
}
