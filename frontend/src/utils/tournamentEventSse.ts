/**
 * 赛事事件 SSE:订阅后收到赛段/场次/打分变化事件即回调。
 * 底层基于统一 sseChannel:同一赛事多个组件共享一条连接,自动指数退避重连,
 * 断线重连成功后自动触发一次全量刷新补偿,补回断线期间错过的事件。
 */

import { subscribeChannel } from './sseChannel';

/** 按赛事维护 回调 -> 取消订阅 的映射,兼容 subscribe/unsubscribe 成对调用 */
const subs = new Map<string, Map<(data: any) => void, () => void>>();

export function subscribeTournamentEvents(
  tournamentId: string | number | null | undefined,
  onMessage: (data: any) => void
) {
  if (tournamentId === null || tournamentId === undefined) {
    return;
  }
  const key = String(tournamentId);
  const baseUrl = (import.meta.env.VITE_APP_BASE_API as string) || '';
  const clientId = (import.meta.env.VITE_APP_CLIENT_ID as string) || '';
  const unsub = subscribeChannel({
    key: `tournament:${key}`,
    buildUrl: () =>
      `${baseUrl}/tournament/event/sse?tournamentId=${encodeURIComponent(key)}&clientid=${clientId}`,
    onMessage,
    // 重连补偿:断线重连后触发一次全量刷新(调用方 loadData 不依赖事件内容)
    onRefresh: () => onMessage(null)
  });
  let m = subs.get(key);
  if (!m) {
    m = new Map();
    subs.set(key, m);
  }
  m.set(onMessage, unsub);
}

export function unsubscribeTournamentEvents(
  tournamentId: string | number | null | undefined,
  onMessage?: (data: any) => void
) {
  if (tournamentId === null || tournamentId === undefined) {
    return;
  }
  const key = String(tournamentId);
  const m = subs.get(key);
  if (!m) {
    return;
  }
  if (onMessage) {
    const unsub = m.get(onMessage);
    if (unsub) {
      unsub();
      m.delete(onMessage);
    }
  } else {
    // 未指定回调:取消该赛事全部订阅
    m.forEach((u) => u());
    m.clear();
  }
  if (m.size === 0) {
    subs.delete(key);
  }
}
