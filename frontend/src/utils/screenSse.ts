import { getToken } from '@/utils/auth';
import { subscribeChannel } from './sseChannel';

// 屏幕控制通道:同一赛事的所有屏幕共享一条 SSE 连接(浏览器对同主机 HTTP/1.1
// 并发连接数约 6 条,若每屏一条连接,控制页 5 屏 + 事件通道就达上限,
// 监视器等新连接会阻塞整个页面的请求)。消息按 screenId 路由给对应订阅者。

interface ScreenSub {
  onMessage: (data: any) => void;
  unsub: () => void;
}

// tournamentId -> screenId -> 订阅信息
const subs = new Map<string, Map<string, ScreenSub>>();
// tournamentId -> terminalId(每赛事一个,重连复用)
const terminalIds = new Map<string, string>();

function generateTerminalId(): string {
  return `terminal_${Date.now()}_${Math.random().toString(36).substring(2, 15)}`;
}

function getTerminalId(tournamentId: string): string {
  let tid = terminalIds.get(tournamentId);
  if (!tid) {
    tid = generateTerminalId();
    terminalIds.set(tournamentId, tid);
  }
  return tid;
}

/**
 * 订阅屏幕控制 SSE:同赛事所有屏幕共享一条连接,按 screenId 路由消息。
 * 同一屏幕重复订阅会先取消旧的。
 */
export function subscribeScreenControl(screenId: string | number, tournamentId: string | number, onMessage: (data: any) => void) {
  const sid = String(screenId);
  const tid = String(tournamentId);
  const token = getToken();
  const clientId = import.meta.env.VITE_APP_CLIENT_ID;
  const baseUrl = import.meta.env.VITE_APP_BASE_API;

  let m = subs.get(tid);
  if (!m) {
    m = new Map();
    subs.set(tid, m);
  }

  const existing = m.get(sid);
  if (existing) {
    existing.unsub();
    m.delete(sid);
  }

  const unsubRaw = subscribeChannel({
    key: `screen-control:${tid}`,
    buildUrl: () => {
      const screenIds = [...(m?.keys() ?? [])];
      if (screenIds.length === 0) {
        return '';
      }
      return `${baseUrl}/tournament/screen/control?Authorization=Bearer ${token}&clientid=${clientId}&screenIds=${encodeURIComponent(
        screenIds.join(',')
      )}&terminalId=${getTerminalId(tid)}&tournamentId=${tid}`;
    },
    onMessage: (data: any) => {
      // 消息带 screenId 时只投递给对应屏幕;不带则广播给该赛事所有屏幕
      if (data && data.screenId) {
        const sub = m?.get(String(data.screenId));
        if (sub) {
          try {
            sub.onMessage(data);
          } catch {
            // 单个订阅者异常不影响其他订阅者
          }
        }
      } else if (m) {
        m.forEach((s) => {
          try {
            s.onMessage(data);
          } catch {
            // 忽略
          }
        });
      }
    }
  });

  m.set(sid, {
    onMessage,
    unsub: () => {
      unsubRaw();
      m.delete(sid);
      if (m.size === 0) {
        subs.delete(tid);
        terminalIds.delete(tid);
      }
    }
  });

  console.log(`[SSE] 屏幕 ${sid} 已加入赛事 ${tid} 控制通道(当前 ${m.size} 个屏幕)`);
}

/**
 * 取消订阅指定屏幕(同赛事最后一个屏幕取消时关闭连接)
 */
export function unsubscribeScreenControl(screenId: string | number) {
  const sid = String(screenId);
  for (const m of subs.values()) {
    const sub = m.get(sid);
    if (sub) {
      sub.unsub();
      return;
    }
  }
}

/**
 * 取消所有屏幕的订阅
 */
export function unsubscribeAllScreens() {
  subs.forEach((m) => {
    m.forEach((s) => {
      try {
        s.unsub();
      } catch {
        // 忽略
      }
    });
  });
  subs.clear();
  terminalIds.clear();
}

/**
 * 获取当前已订阅的屏幕数量
 */
export function getConnectedScreenCount(): number {
  let count = 0;
  subs.forEach((m) => {
    count += m.size;
  });
  return count;
}

/**
 * 检查屏幕是否已订阅
 */
export function isScreenConnected(screenId: string | number): boolean {
  const sid = String(screenId);
  for (const m of subs.values()) {
    if (m.has(sid)) {
      return true;
    }
  }
  return false;
}
