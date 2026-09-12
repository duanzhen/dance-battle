/**
 * 统一 SSE 客户端。
 *
 * 特性:
 * - 按 key 共享连接:同 key 多个订阅者复用一条 EventSource,最后一个取消时才关闭;
 * - 指数退避重连(带抖动上限),替代浏览器默认固定间隔重连,避免重连风暴;
 * - 重连补偿:连接(含断线重连)建立后触发 onRefresh,让订阅方做一次全量刷新,
 *   补回断线期间错过的事件;
 * - 移动端适配:锁屏/切应用/网络切换导致连接被系统挂起或静默断开时,
 *   页面恢复可见(visibilitychange/pageshow)、网络恢复(online)按需重连;
 *   另有定时健康检查 + 空闲看门狗(后端 60s 命名事件 ping 心跳),兜底"半死"连接;
 * - 只在连接确实不健康时才重连:连接正常时切标签页/回前台不再强拆重连,
 *   避免"重连 → 全量刷新"把页面刷得一直闪;
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
/** 空闲超时:后端每 60s 发送 ping 心跳,超过 150s 未收到任何数据视为连接已死 */
const IDLE_TIMEOUT = 150000;

/** 连接是否健康:存在、未关闭、且最近收到过消息或心跳 */
const isHealthy = (conn: SseChannelConn) => {
  const es = conn.es;
  if (!es || es.readyState === EventSource.CLOSED) {
    return false;
  }
  return Date.now() - conn.lastEventAt <= IDLE_TIMEOUT;
};

/** 只在连接不健康时重连(回前台/网络恢复等场景调用) */
const reconnectIfUnhealthy = (conn: SseChannelConn) => {
  if (conn.closed) return;
  // 已有重连计划(退避等待中)时不打断,避免把指数退避重置成"每 20s 猛重试一次"
  if (conn.retryTimer) return;
  if (isHealthy(conn)) return;
  reconnect(conn, true);
};

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
  reconnectIfUnhealthy(conn);
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

  // 心跳:仅更新存活时间,不触发业务刷新(命名事件不进 onmessage)
  es.addEventListener('ping', () => {
    conn.lastEventAt = Date.now();
  });

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
      channels.forEach(reconnectIfUnhealthy);
    }
  });
  window.addEventListener('pageshow', () => {
    channels.forEach(reconnectIfUnhealthy);
  });
  window.addEventListener('online', () => {
    channels.forEach(reconnectIfUnhealthy);
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
