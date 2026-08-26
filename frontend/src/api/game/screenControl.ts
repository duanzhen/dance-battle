import request from '@/utils/request';

// 投射场景到屏幕(使用赛事导播专用凭证 authKey,不走管理员 JWT)
export function projectSceneToScreen(screenId: string, sceneId: string | number, tournamentId: string | number, authKey: string) {
  return request({
    url: '/tournament/screen/scene',
    method: 'post',
    data: {
      screenId,
      sceneId,
      tournamentId: String(tournamentId)
    },
    headers: { Authorization: 'Bearer ' + authKey, isToken: false }
  });
}

// 清除屏幕投射
export function clearScreenScene(screenId: string, tournamentId: string | number, authKey: string) {
  return request({
    url: '/tournament/screen/scene',
    method: 'delete',
    params: { screenId, tournamentId: String(tournamentId) },
    headers: { Authorization: 'Bearer ' + authKey, isToken: false }
  });
}
