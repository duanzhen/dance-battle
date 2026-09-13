import axios from 'axios';
import { attachEnvelope } from '@/utils/apiEnvelope';

/**
 * 手机导播台 API:使用赛事 auth_key 认证(Authorization: Bearer auth_key),
 * 与管理员接口完全隔离。
 */

let directorAuthKey: string | null = null;

export function setDirectorAuthKey(key: string) {
  directorAuthKey = key;
}

// 统一响应拆包:与管理端 utils/request 一致,请求结果就是 { code, msg, data } 信封
const directorRequest = attachEnvelope(axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8',
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  }
}) as any);

directorRequest.interceptors.request.use((config) => {
  if (directorAuthKey) {
    config.headers.Authorization = 'Bearer ' + directorAuthKey;
  }
  return config;
});

/** 获取当前认证赛事信息 */
export function getDirectorTournament() {
  return directorRequest({
    url: '/game/director/tournament',
    method: 'get'
  });
}

/** 赛段列表(仅当前赛事) */
export function listDirectorStages(tournamentId: string | number) {
  return directorRequest({
    url: '/game/director/stage/list',
    method: 'get',
    params: { tournamentId }
  });
}

/** 场次列表(按赛段) */
export function listDirectorMatches(stageId: string | number) {
  return directorRequest({
    url: '/game/director/match/list',
    method: 'get',
    params: { stageId }
  });
}

/** 开始赛段 */
export function directorStartStage(id: string | number) {
  return directorRequest({
    url: `/game/director/stage/${id}/start`,
    method: 'put'
  });
}

/** 跳过中间态确认晋级(开启「跳过中间态确认」配置时可用):按当前预排把晋级者写入下一赛段 */
export function directorAdvanceStage(id: string | number) {
  return directorRequest({
    url: `/game/director/stage/${id}/advance`,
    method: 'post'
  });
}

/** 完成赛段 */
export function directorCompleteStage(id: string | number) {
  return directorRequest({
    url: `/game/director/stage/${id}/complete`,
    method: 'put'
  });
}

/** 擂台赛:下一场(胜者守擂、败者排到队尾;平局时擂主与挑战者均排到队尾) */
export function directorArenaNext(id: string | number) {
  return directorRequest({
    url: `/game/director/stage/${id}/arena-next`,
    method: 'post'
  });
}

/** 擂台赛临时弃权:该选手本轮跳过、排到队尾,后续仍参与排队与排名 */
export function directorArenaTempWithdraw(id: string | number, competitorId: string | number) {
  return directorRequest({
    url: `/game/director/stage/${id}/arena-temp-withdraw`,
    method: 'post',
    params: { competitorId }
  });
}

/** 开始场次 */
export function directorStartMatch(id: string | number) {
  return directorRequest({
    url: `/game/director/match/${id}/start`,
    method: 'post'
  });
}

/** 标记场次当前上场选手(海选大屏):MC 点击选手名字后调用,仅标记并广播;competitorId 传 null 清除 */
export function directorSetCurrentCompetitor(id: string | number, competitorId: string | number | null) {
  return directorRequest({
    url: `/game/director/match/${id}/current-competitor`,
    method: 'put',
    data: { competitorId }
  });
}

/** 查询场次当前标记的上场选手(导播台高亮用) */
export function directorGetCurrentCompetitor(id: string | number) {
  return directorRequest({
    url: `/game/director/match/${id}/current-competitor`,
    method: 'get'
  });
}

/** 取消开始场次(误触回退):GAMING → PENDING,清空本场已提交分数/结果 */
export function directorCancelStartMatch(id: string | number) {
  return directorRequest({
    url: `/game/director/match/${id}/cancel-start`,
    method: 'post'
  });
}

/** 回退单场结算 */
export function directorResetMatch(id: string | number) {
  return directorRequest({
    url: `/game/director/match/${id}/reset`,
    method: 'post'
  });
}

/** 提交比赛结果 */
export function directorSubmitResult(id: string | number, data: any) {
  return directorRequest({
    url: `/game/director/match/${id}/submit-result`,
    method: 'post',
    data
  });
}

/** 确认公布结果 */
export function directorPublishResult(id: string | number) {
  return directorRequest({
    url: `/game/director/match/${id}/publish-result`,
    method: 'post'
  });
}

// ==================== 自由对抗(手动加场 + 手动选晋级) ====================

/** 本赛段参赛选手(选人对战 / 勾选晋级用) */
export function listDirectorStageCompetitors(stageId: string | number) {
  return directorRequest({
    url: `/game/director/stage/${stageId}/competitors`,
    method: 'get'
  });
}

/** 手动添加一场对战(线下抽签/指认确定的两名选手) */
export function directorCreateFreeMatch(stageId: string | number, data: { competitorAId: string | number; competitorBId: string | number }) {
  return directorRequest({
    url: `/game/director/stage/${stageId}/free-match`,
    method: 'post',
    data
  });
}

/** 删除一场对战(误加时使用,已结算场次需先重置) */
export function directorDeleteFreeMatch(matchId: string | number) {
  return directorRequest({
    url: `/game/director/match/${matchId}/free-match`,
    method: 'delete'
  });
}

/** 手动选择晋级者(任意人数),未选中的标记淘汰 */
export function directorSaveFreeMatchAdvancers(stageId: string | number, competitorIds: (string | number)[]) {
  return directorRequest({
    url: `/game/director/stage/${stageId}/free-match-advancers`,
    method: 'put',
    data: { competitorIds }
  });
}
