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
 * - 回到前台补偿:锁屏/切应用期间 JS 被挂起,这期间的事件一条都收不到,
 *   因此只要在后台待够一段时间,恢复时除重连外还会主动补一次 onRefresh 全量刷新
 *   (裁判/导播解锁后必须看到当前状态,而不是锁屏前的旧画面);
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
  /** 进入后台的时间点(null=当前在前台),用于恢复时判断是否需要补偿刷新 */
  hiddenAt: number | null;
  /** 上次「恢复前台补偿刷新」的时间,用于去重 visibilitychange + pageshow 的双触发 */
  lastResumeRefreshAt: number;
}

const channels = new Map<string, SseChannelConn>();

const MAX_RETRY_DELAY = 30000;
const HEARTBEAT_CHECK_INTERVAL = 20000;
/** 空闲超时:后端每 60s 发送 ping 心跳,超过 150s 未收到任何数据视为连接已死 */
const IDLE_TIMEOUT = 150000;
/** 后台停留超过该时长,恢复前台时补一次全量刷新(短于它的切标签不必刷新) */
const RESUME_REFRESH_MIN_HIDDEN_MS = 5000;
/** 同一次恢复可能同时触发 visibilitychange 与 pageshow,用它节流去重 */
const RESUME_REFRESH_THROTTLE_MS = 2000;

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

/** 计划一次开连接(去重):delay 毫秒后调用 open,期间其它重连请求会被忽略 */
const scheduleOpen = (conn: SseChannelConn, delay: number) => {
  if (conn.closed) return;
  if (conn.retryTimer) {
    clearTimeout(conn.retryTimer);
  }
  conn.retryTimer = setTimeout(() => {
    conn.retryTimer = null;
    open(conn);
  }, delay);
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
  const jitter = Math.floor(Math.random() * 1000);
  scheduleOpen(conn, immediate ? 0 : conn.retryDelay + jitter);
  if (!immediate) {
    conn.retryDelay = Math.min(conn.retryDelay * 2, MAX_RETRY_DELAY);
  }
};

/** 健康检查:连接已关闭/缺失,或长时间未收到任何数据(含心跳)时强制重连 */
const ensureAlive = (conn: SseChannelConn) => {
  if (conn.closed) return;
  reconnectIfUnhealthy(conn);
};

/** 页面进入后台/被隐藏:记录时间点,供恢复时判断是否需要补偿刷新 */
const markHidden = () => {
  const now = Date.now();
  channels.forEach((conn) => {
    conn.hiddenAt = now;
  });
};

/**
 * 页面回到前台(解锁 / 切回应用 / 从 bfcache 恢复)。
 *
 * <p>手机锁屏或切到其它应用时浏览器会把 JS 挂起:期间后端推送的事件一条也收不到,
 * 而 TCP 连接可能"看起来还是开的"(对端没发 FIN)。所以这里做两件事:
 * 连接不健康就重连(重连成功后会触发一次 onRefresh 补偿);只要在后台待了足够久,
 * 再额外补一次全量刷新——因为「没收到事件」不等于「数据没变」,裁判/导播解锁后
 * 必须看到当前真实状态,而不是锁屏前的旧画面。</p>
 */
const handleResume = () => {
  const now = Date.now();
  channels.forEach((conn) => {
    const hiddenFor = conn.hiddenAt == null ? 0 : now - conn.hiddenAt;
    conn.hiddenAt = null;
    // 1) 连接可能已死:按健康度决定是否重连
    reconnectIfUnhealthy(conn);
    // 2) 后台期间的变更需要补拉,与连接是否健康无关
    if (hiddenFor < RESUME_REFRESH_MIN_HIDDEN_MS) {
      return;
    }
    if (now - conn.lastResumeRefreshAt < RESUME_REFRESH_THROTTLE_MS) {
      return;
    }
    conn.lastResumeRefreshAt = now;
    conn.listeners.forEach((l) => {
      try {
        l.onRefresh?.();
      } catch {
        // 单个订阅者异常不影响其他订阅者
      }
    });
  });
};

const open = (conn: SseChannelConn) => {
  if (conn.closed) return;
  let url = '';
  try {
    url = conn.buildUrl() || '';
  } catch {
    url = '';
  }
  if (!url) {
    // 订阅方暂时给不出地址(如屏幕控制通道刚订阅、屏幕列表还没写入)时稍后重试;
    // 直接 new EventSource('') 会把当前页面地址当成 SSE 请求,产生一次无意义的失败连接
    scheduleOpen(conn, 500);
    return;
  }
  try {
    conn.es?.close();
  } catch {
    // 忽略关闭旧连接异常
  }
  let es: EventSource;
  try {
    es = new EventSource(url);
  } catch {
    // URL 非法等同步异常:按退避重连,不让整个通道挂掉
    scheduleOpen(conn, conn.retryDelay);
    conn.retryDelay = Math.min(conn.retryDelay * 2, MAX_RETRY_DELAY);
    return;
  }
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
      handleResume();
    } else {
      markHidden();
    }
  });
  // pagehide 覆盖「切应用被冻结 / 进入 bfcache」,pageshow 覆盖恢复
  window.addEventListener('pagehide', markHidden);
  window.addEventListener('pageshow', handleResume);
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
      lastEventAt: Date.now(),
      hiddenAt: null,
      lastResumeRefreshAt: 0
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
