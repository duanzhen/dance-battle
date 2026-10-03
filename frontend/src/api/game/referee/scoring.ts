import axios from 'axios';
import { attachEnvelope } from '@/utils/apiEnvelope';

let refereeAuthKey: string | null = null;

export function setRefereeAuthKey(key: string) {
  refereeAuthKey = key;
}

// 统一响应拆包:与管理端 utils/request 一致,请求结果就是 { code, msg, data } 信封
const refereeRequest = attachEnvelope(axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8',
    'clientid': import.meta.env.VITE_APP_CLIENT_ID
  }
}) as any);

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

/**
 * 轻量取当前场次各参赛方的累计分/名次:打分事件的局部刷新用,
 * 替代每次打分都全量拉 my-match。
 */
export function getRefereeMatchScores(matchId: string | number) {
  return refereeRequest({
    url: `/game/referee-match/${matchId}/scores`,
    method: 'get'
  });
}
