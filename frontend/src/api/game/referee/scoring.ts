import axios from 'axios';

let refereeAuthKey: string | null = null;

export function setRefereeAuthKey(key: string) {
  refereeAuthKey = key;
}

const refereeRequest = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8',
    'clientid': import.meta.env.VITE_APP_CLIENT_ID
  }
});

refereeRequest.interceptors.request.use((config) => {
  if (refereeAuthKey) {
    config.headers.Authorization = 'Bearer ' + refereeAuthKey;
  }
  return config;
});

/**
 * 获取裁判对应的当前比赛信息
 * 未传参时自动定位当前进行的 赛段/场次;也可显式指定 stageId / matchId 切换
 */
export function getRefereeMyMatch(stageId?: string | number, matchId?: string | number) {
  return refereeRequest({
    url: '/game/referee-match/my-match',
    method: 'get',
    params: {
      ...(stageId !== undefined ? { stageId } : {}),
      ...(matchId !== undefined ? { matchId } : {})
    }
  });
}

/**
 * 裁判提交打分
 */
export function submitRefereeScore(matchId: string | number, data: any) {
  return refereeRequest({
    url: `/game/referee-match/${matchId}/submit-score`,
    method: 'post',
    data
  });
}
