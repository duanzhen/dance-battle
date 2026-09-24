/**
 * 统一 SSE 客户端。
 *
 * 特性:
 * - 按 key 共享连接:同 key 多个订阅者复用一条 EventSource,最后一个取消时才关闭;
 * - 固定 3s 间隔重连,永不停止:连接失败后每 3s 重试一次,直到重新连上;
 * - 网络恢复/回到前台时立即重连,不等下一次 3s 周期;
 * - 重连补偿:连接(含断线重连)建立后触发 onRefresh,让订阅方做一次全量刷新,
 *   补回断线期间错过的事件;
 * - 移动端适配:锁屏/切应用/网络切换导致连接被系统挂起或静默断开时,
 *   页面恢复可见(visibilitychange/pageshow)、网络恢复(online)按需重连;
 *   另有定时健康检查 + 空闲看门狗(后端 15s 命名事件 ping 心跳),兜底"半死"连接;
 * - 回到前台补偿:锁屏/切应用期间 JS 被挂起,这期间的事件一条都收不到,
 *   因此只要在后台待够一段时间,恢复时除重连外还会主动补一次 onRefresh 全量刷新
 *   (裁判/导播解锁后必须看到当前状态,而不是锁屏前的旧画面);
 * - 只在连接确实不健康时才重连:连接正常时切标签页/回前台不再强拆重连,
 *   避免"重连 → 全量刷新"把页面刷得一直闪;
 * - 首次连接建立不触发 onRefresh(订阅方挂载时已自行拉取),避免重复请求。
 */

/**
 * 对外暴露的连接状态(绿/黄/红标识的唯一来源):
 * - connecting:正在建连 / 等待首次心跳确认 / 等待下一次重试 → 黄色呼吸;
 * - open:已连上且最近 {@link HEARTBEAT_FRESH_MS} 内收到过心跳(或业务事件)→ 绿色;
 * - error:通道已关闭、不再重连(订阅方全部取消订阅)→ 红色。
 *
 * <p>注意:断线后的等待与重试过程都归入 connecting(黄),不显示红色——重试永不停止,
 * "红色"只留给真正停止重连的终态。</p>
 */
export type SseStatus = 'connecting' | 'open' | 'error';

interface SseListener {
  onMessage: (data: any) => void;
  onRefresh: (() => void) | null;
  onStatus: ((status: SseStatus) => void) | null;
}

interface SseChannelConn {
  es: EventSource | null;
  buildUrl: () => string;
  listeners: Set<SseListener>;
  retryTimer: ReturnType<typeof setTimeout> | null;
  reopened: boolean;
  closed: boolean;
  /** 最近一次「连接可用」的时间(建连成功或收到数据):仅用于健康检查/重连判定 */
  lastEventAt: number;
  /** 当前这条连接最近一次收到数据(心跳/业务事件)的时间;0=本次连接还没收到过,尚未"确认连上" */
  lastLiveAt: number;
  /** 当前对外状态 */
  status: SseStatus;
  /** 进入后台的时间点(null=当前在前台),用于恢复时判断是否需要补偿刷新 */
  hiddenAt: number | null;
  /** 上次「恢复前台补偿刷新」的时间,用于去重 visibilitychange + pageshow 的双触发 */
  lastResumeRefreshAt: number;
}

const channels = new Map<string, SseChannelConn>();

/** 自动重连间隔:失败后固定每 3s 重试一次,永不停止(活动现场优先尽快接回) */
const RETRY_DELAY_MS = 3000;
const HEARTBEAT_CHECK_INTERVAL = 10000;
/** 心跳新鲜窗口:后端每 15s 发一次 ping,该窗口内收到过心跳即视为"确认已连上"(绿灯) */
const HEARTBEAT_FRESH_MS = 20000;
/**
 * 空闲超时:后端每 15s 发 ping,超过 35s(容忍一次丢失)未收到任何数据即视为连接已死,触发重连。
 * 该值决定了"标识变红(20s 无心跳)之后多久开始自动重连",取 35s 兼顾"尽快恢复"与"不因偶发丢一次心跳就拆连接"。
 */
const IDLE_TIMEOUT = 35000;
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

/** 状态变化时通知订阅者(相同状态不重复通知,避免心跳把页面刷成无谓的响应式更新) */
const setStatus = (conn: SseChannelConn, status: SseStatus) => {
  if (conn.status === status) return;
  conn.status = status;
  conn.listeners.forEach((l) => {
    try {
      l.onStatus?.(status);
    } catch {
      // 忽略单个订阅者异常
    }
  });
};

/**
 * 由连接现状推导三态:
 * 只有「通道打开 且 最近 20s 内收到过心跳/事件」才算真正连上(绿)。
 * 其余一律算"连接中"(黄):正在握手、刚握手还没等到首条心跳、已断开等待下一次重试、
 * 心跳超时——它们都在"正在尝试/即将重连"的范围内,显示黄色而不是红色。
 */
const computeStatus = (conn: SseChannelConn): SseStatus => {
  if (conn.closed) return 'error';
  const es = conn.es;
  // 未建连 / 建连中 / 已断开等待重试:都算"连接中"
  if (!es || es.readyState !== EventSource.OPEN) return 'connecting';
  // 握手成功但还没等到首条心跳:仍算连接中,避免没过心跳就先报绿灯
  if (conn.lastLiveAt === 0) return 'connecting';
  return Date.now() - conn.lastLiveAt <= HEARTBEAT_FRESH_MS ? 'open' : 'connecting';
};

/** 收到数据(心跳/业务事件)或状态被动变化(心跳超时)后重算并广播状态 */
const refreshStatus = (conn: SseChannelConn) => {
  setStatus(conn, computeStatus(conn));
};

/** 只在连接不健康时重连,且不打断已在排队的下一次重试(仅周期巡检使用) */
const reconnectIfUnhealthy = (conn: SseChannelConn) => {
  if (conn.closed) return;
  // 已有重连计划(3s 周期等待中)时不打断,避免每次巡检都重复排一次重连
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

/** 关闭旧连接并重开:immediate=true 时立刻重开,否则等一个固定重连周期(3s)后再试 */
const reconnect = (conn: SseChannelConn, immediate = false) => {
  if (conn.closed) return;
  try {
    conn.es?.close();
  } catch {
    // 忽略关闭旧连接异常
  }
  scheduleOpen(conn, immediate ? 0 : RETRY_DELAY_MS);
};

/**
 * 外部条件变化(网络恢复、回到前台、页面重新可见)时立即重连:不等下一个重连周期。
 *
 * <p>此前这些场景调用的是 {@link reconnectIfUnhealthy},而它在"已有重连计划(等待下一次重试)"时
 * 直接返回——于是网恢复了也得把剩下的等待耗完才重连,现场表现就是"网早回来了,页面还红着"。
 * 这里改为:只要连接不健康,就清掉待执行的重连计时器并立刻重连一次。</p>
 *
 * <p>注意判定顺序:先判断健康再清计时器——若连接其实健康却把计时器清掉,就再没人去重连了。</p>
 */
const reconnectNow = (conn: SseChannelConn) => {
  if (conn.closed) return;
  if (isHealthy(conn)) return;
  if (conn.retryTimer) {
    clearTimeout(conn.retryTimer);
    conn.retryTimer = null;
  }
  reconnect(conn, true);
};

/** 周期巡检:先按心跳新鲜度刷新对外状态(绿灯可能因丢心跳自动转红),再不健康时强制重连 */
const ensureAlive = (conn: SseChannelConn) => {
  if (conn.closed) return;
  refreshStatus(conn);
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
    // 1) 连接可能已死:不健康就立即重连,不等下一次 3s 周期
    reconnectNow(conn);
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
  // 每次(重)建连都先回到"连接中":本次连接尚未收到心跳,不能沿用上一次的绿灯
  conn.lastLiveAt = 0;
  setStatus(conn, 'connecting');
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
    // URL 非法等同步异常:等一个重连周期后再试,不让整个通道挂掉
    scheduleOpen(conn, RETRY_DELAY_MS);
    return;
  }
  conn.es = es;

  // 心跳:仅更新存活时间,不触发业务刷新(命名事件不进 onmessage)
  es.addEventListener('ping', () => {
    conn.lastEventAt = Date.now();
    conn.lastLiveAt = Date.now();
    refreshStatus(conn);
  });

  es.onopen = () => {
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
    // 握手成功还不算"确认连上":等首条心跳(或业务事件)到达才转绿
    refreshStatus(conn);
  };

  es.onmessage = (e) => {
    conn.lastEventAt = Date.now();
    conn.lastLiveAt = Date.now();
    refreshStatus(conn);
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
    // 断开即进入重连流程:立刻转黄(连接中),而不是先亮红再等 3s —— 重试永不停止
    // 固定 3s 后再重连(由 reconnect 统一调度,永不停止)
    setStatus(conn, 'connecting');
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
    // 网络恢复:立即重连,不等下一次 3s 周期
    channels.forEach(reconnectNow);
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
 * @param options.onStatus 连接状态回调('connecting'/'open'/'error'),可省略
 * @returns 取消订阅函数
 */
export function subscribeChannel(options: {
  key: string;
  buildUrl: () => string;
  onMessage: (data: any) => void;
  onRefresh?: () => void;
  onStatus?: (status: SseStatus) => void;
}): () => void {
  let conn = channels.get(options.key);
  if (!conn) {
    conn = {
      es: null,
      buildUrl: options.buildUrl,
      listeners: new Set(),
      retryTimer: null,
      reopened: false,
      closed: false,
      lastEventAt: Date.now(),
      lastLiveAt: 0,
      status: 'connecting',
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
  // 立即补一次当前状态:订阅者可能在连接已经建立/变化之后才挂上监听
  try {
    listener.onStatus?.(conn.status);
  } catch {
    // 忽略单个订阅者异常
  }

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
