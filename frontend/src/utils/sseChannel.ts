/**
 * 统一 SSE 客户端。
 *
 * 特性:
 * - 按 key 共享连接:同 key 多个订阅者复用一条 EventSource,最后一个取消时才关闭;
 * - 指数退避重连(带抖动上限),替代浏览器默认固定间隔重连,避免重连风暴;
 * - 重连补偿:连接(含断线重连)建立后触发 onRefresh,让订阅方做一次全量刷新,
 *   补回断线期间错过的事件;
 * - 移动端适配:锁屏/切应用/网络切换导致连接被系统挂起或静默断开时,
 *   页面恢复可见(visibilitychange/pageshow)、网络恢复(online)立即重连;
 *   另有定时健康检查 + 空闲看门狗(后端 60s 心跳注释),兜底"半死"连接;
 * - 首次连接建立不触发 onRefresh(订阅方挂载时已自行拉取),避免重复请求。
 */

interface SseListener {
  onMessage: (data: any) => void;
  onRefresh: (() => void) | null;
  onStatus: ((status: 'open' | 'error') => void) | null;
}

interface SseChannelConn {
  es: EventSource | null;
  buildUrl: () => string;
  listeners: Set<SseListener>;
  retryDelay: number;
  retryTimer: ReturnType<typeof setTimeout> | null;
  reopened: boolean;
  closed: boolean;
  lastEventAt: number;
}

const channels = new Map<string, SseChannelConn>();

const MAX_RETRY_DELAY = 30000;
const HEARTBEAT_CHECK_INTERVAL = 20000;
/** 空闲超时:后端每 60s 发送 comment 心跳,超过 90s 未收到任何数据视为连接已死 */
const IDLE_TIMEOUT = 90000;

/** 关闭旧连接并按(可选重置)退避策略重开;immediate=true 时立即重连并重置退避 */
const reconnect = (conn: SseChannelConn, immediate = false) => {
  if (conn.closed) return;
  try {
    conn.es?.close();
  } catch {
    // 忽略关闭旧连接异常
  }
  if (immediate) {
    conn.retryDelay = 1000;
  }
  if (conn.retryTimer) {
    clearTimeout(conn.retryTimer);
  }
  const jitter = Math.floor(Math.random() * 1000);
  conn.retryTimer = setTimeout(() => open(conn), immediate ? 0 : conn.retryDelay + jitter);
  if (!immediate) {
    conn.retryDelay = Math.min(conn.retryDelay * 2, MAX_RETRY_DELAY);
  }
};

/** 健康检查:连接已关闭/缺失,或长时间未收到任何数据(含心跳)时强制重连 */
const ensureAlive = (conn: SseChannelConn) => {
  if (conn.closed) return;
  const es = conn.es;
  if (!es || es.readyState === EventSource.CLOSED) {
    reconnect(conn, true);
    return;
  }
  if (Date.now() - conn.lastEventAt > IDLE_TIMEOUT) {
    reconnect(conn, true);
  }
};

const open = (conn: SseChannelConn) => {
  if (conn.closed) return;
  try {
    conn.es?.close();
  } catch {
    // 忽略关闭旧连接异常
  }
  const es = new EventSource(conn.buildUrl());
  conn.es = es;

  es.onopen = () => {
    conn.retryDelay = 1000;
    conn.lastEventAt = Date.now();
    if (conn.reopened) {
      // 断线重连成功:补偿刷新,补回断线期间错过的事件
      conn.listeners.forEach((l) => {
        try {
          l.onRefresh?.();
        } catch {
          // 单个订阅者异常不影响其他订阅者
        }
      });
    }
    conn.reopened = true;
    conn.listeners.forEach((l) => {
      try {
        l.onStatus?.('open');
      } catch {
        // 忽略单个订阅者异常
      }
    });
  };

  es.onmessage = (e) => {
    conn.lastEventAt = Date.now();
    let data: any;
    try {
      data = JSON.parse(e.data);
    } catch {
      data = e.data;
    }
    conn.listeners.forEach((l) => {
      try {
        l.onMessage(data);
      } catch {
        // 忽略单个订阅者回调异常
      }
    });
  };

  es.onerror = () => {
    conn.listeners.forEach((l) => {
      try {
        l.onStatus?.('error');
      } catch {
        // 忽略单个订阅者异常
      }
    });
    // 指数退避重连(带抖动,降低并发重连对服务端的瞬时压力)
    reconnect(conn);
  };
};

// 全局生命周期兜底:锁屏/切应用恢复、网络恢复、定时健康检查
if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      channels.forEach((c) => reconnect(c, true));
    }
  });
  window.addEventListener('pageshow', () => {
    channels.forEach((c) => reconnect(c, true));
  });
  window.addEventListener('online', () => {
    channels.forEach((c) => reconnect(c, true));
  });
  setInterval(() => {
    channels.forEach(ensureAlive);
  }, HEARTBEAT_CHECK_INTERVAL);
}

/**
 * 订阅一个 SSE 通道。
 * @param options.key 连接复用键(如赛事ID),同 key 共享一条连接
 * @param options.buildUrl 构建 SSE URL(重连时重新调用)
 * @param options.onMessage 事件回调
 * @param options.onRefresh 连接(含重连)建立后的全量刷新回调,可省略
 * @param options.onStatus 连接状态回调('open'/'error'),可省略
 * @returns 取消订阅函数
 */
export function subscribeChannel(options: {
  key: string;
  buildUrl: () => string;
  onMessage: (data: any) => void;
  onRefresh?: () => void;
  onStatus?: (status: 'open' | 'error') => void;
}): () => void {
  let conn = channels.get(options.key);
  if (!conn) {
    conn = {
      es: null,
      buildUrl: options.buildUrl,
      listeners: new Set(),
      retryDelay: 1000,
      retryTimer: null,
      reopened: false,
      closed: false,
      lastEventAt: Date.now()
    };
    channels.set(options.key, conn);
    open(conn);
  }
  const listener: SseListener = {
    onMessage: options.onMessage,
    onRefresh: options.onRefresh ?? null,
    onStatus: options.onStatus ?? null
  };
  conn.listeners.add(listener);

  return () => {
    const cur = channels.get(options.key);
    if (!cur) return;
    cur.listeners.delete(listener);
    if (cur.listeners.size === 0) {
      cur.closed = true;
      if (cur.retryTimer) {
        clearTimeout(cur.retryTimer);
      }
      try {
        cur.es?.close();
      } catch {
        // 忽略关闭异常
      }
      channels.delete(options.key);
    }
  };
}
