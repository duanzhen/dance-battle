import request from '@/utils/request';

// 投射场景到屏幕
export function projectSceneToScreen(screenId: string, sceneId: string | number) {
  return request({
    url: '/tournament/screen/scene',
    method: 'post',
    data: {
      screenId,
      sceneId
    }
  });
}

// 清除屏幕投射
export function clearScreenScene(screenId: string) {
  return request({
    url: '/tournament/screen/scene',
    method: 'delete',
    params: { screenId }
  });
}
