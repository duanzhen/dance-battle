/**
 * 赛事事件 SSE:订阅后收到赛段/场次/打分变化事件即回调。
 * 底层基于统一 sseChannel:同一赛事多个组件共享一条连接,自动指数退避重连,
 * 断线重连成功后自动触发一次全量刷新补偿,补回断线期间错过的事件。
 */

import { subscribeChannel } from './sseChannel';

/** 按赛事维护 回调 -> 取消订阅 的映射,兼容 subscribe/unsubscribe 成对调用 */
const subs = new Map<string, Map<(data: any) => void, () => void>>();

/** 事件合并窗口(毫秒):同一窗口内连续多条事件只触发一次刷新 */
const COALESCE_MS = 150;

/**
 * 合并短时间内的连续事件:打分/签到/结算常常一次请求连发多条事件,
 * 逐条刷新会让页面反复重建(视觉上就是"一直闪"),这里合并为一次。
 */
function coalesce(handler: (data: any) => void) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pending: any = undefined;
  let hasPending = false;

  const flush = () => {
    timer = null;
    if (!hasPending) return;
    const data = pending;
    hasPending = false;
    pending = undefined;
    handler(data);
  };

  return (data: any) => {
    if (data === null) {
      // 重连补偿:立即刷新,并吃掉窗口内待处理的事件
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      hasPending = false;
      pending = undefined;
      handler(null);
      return;
    }
    pending = data;
    hasPending = true;
    if (!timer) {
      timer = setTimeout(flush, COALESCE_MS);
    }
  };
}

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
  const listener = coalesce(onMessage);
  const unsub = subscribeChannel({
    key: `tournament:${key}`,
    buildUrl: () =>
      `${baseUrl}/tournament/event/sse?tournamentId=${encodeURIComponent(key)}&clientid=${clientId}`,
    onMessage: listener,
    // 重连补偿:断线重连后触发一次全量刷新(调用方 loadData 不依赖事件内容)
    onRefresh: () => listener(null)
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
