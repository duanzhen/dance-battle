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
 *   另有定时健康检查 + 空闲看门狗(后端 15s 带时间戳的命名事件 ping 心跳),兜底"半死"连接;
 * - 回到前台用「本机时间戳」判断离开了多久:超过阈值就主动静默重连一次,由重连成功触发的
 *   onRefresh 重新调接口取最新数据(不整页重建);离开很短则只在连接不健康时才重连。
 *   用时间戳而不是定时器/计数器:JS 被挂起时定时器不会执行,会误判成"没过去多久";
 *   也不依赖服务端时间戳,避免设备时钟不同步导致的误判;
 * - 只在连接确实不健康时才重连:连接正常时切标签页/回前台不再强拆重连,
 *   避免"重连 → 全量刷新"把页面刷得一直闪;
 * - 首次连接建立不触发 onRefresh(订阅方挂载时已自行拉取),避免重复请求。
 */

/**
 * 对外暴露的连接状态(绿/黄/红标识的唯一来源):
 * - connecting:正在建连 / 等待首次心跳确认 / 等待下一次重试 → 黄色呼吸;
 * - open:已连上且最近 {@link HEARTBEAT_FRESH_MS} 内收到过新鲜心跳 → 绿色;
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
  /** 页面进入后台/被隐藏的时刻(本机墙钟时间戳);null=当前在前台。JS 被挂起期间墙钟照样在走 */
  hiddenAt: number | null;
  /** 当前对外状态 */
  status: SseStatus;
  /** 重连后待补偿刷新:标记后由"首条心跳确认连上"时触发,而不是传输层一建连就拉数据 */
  refreshOnConfirm: boolean;
}

const channels = new Map<string, SseChannelConn>();

/** 自动重连间隔:失败后固定每 3s 重试一次,永不停止(活动现场优先尽快接回) */
const RETRY_DELAY_MS = 3000;
const HEARTBEAT_CHECK_INTERVAL = 10000;
/** 心跳新鲜窗口:后端每 15s 发一次 ping,窗口内收到过心跳即视为"确认已连上"(绿灯) */
const HEARTBEAT_FRESH_MS = 20000;
/**
 * 心跳空闲超时:后端每 15s 发 ping,超过 35s(容忍一次丢失)没有心跳即视为连接已死,触发重连。
 * 取 35s 兼顾"尽快恢复"与"不因偶发丢一次心跳就拆连接"。
 */
const IDLE_TIMEOUT = 35000;
/**
 * 离开前台超过该时长才判定"后台期间很可能被挂起过",回到前台时主动重连一次并重拉数据。
 * 取 30s:短暂切应用/回消息(几秒)不触发任何重连与重拉,避免"一切回来页面数据就重建"的观感;
 * 只有真正锁屏、长时间切走才重连。判定用页面隐藏时刻的本机时间戳,与设备/服务端时钟是否同步无关。
 */
const RESUME_RECONNECT_MIN_HIDDEN_MS = 30000;
/** 连接是否健康:存在、未关闭、且最近收到过心跳(心跳按本机时间戳记账) */
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
 * 只有「通道打开 且 最近 20s 内收到过新鲜心跳」才算真正连上(绿)。
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

/** 页面进入后台/被隐藏:记录本机墙钟时间戳(JS 被挂起时它照样在走,不是定时器计数) */
const markHidden = () => {
  const now = Date.now();
  channels.forEach((conn) => {
    conn.hiddenAt = now;
  });
};

/**
 * 页面回到前台(解锁 / 切回应用 / 从 bfcache 恢复)或网络恢复。
 *
 * <p>用「隐藏时刻的本机时间戳」算出离开了多久:定时器/计数器在 JS 被挂起时不会执行,
 * 会把几分钟的挂起误判成"没过去多久";墙钟时间戳不受影响。这里也不依赖服务端时间戳,
 * 因此设备时钟是否同步都不影响判定。恢复后只重拉数据、不做整页重建:</p>
 * <ul>
 *   <li>离开够久(≥ {@link RESUME_RECONNECT_MIN_HIDDEN_MS})说明后台期间多半被挂起过:
 *       主动静默重连一次,重连成功后由 onopen 触发 onRefresh 重新调接口取最新数据;</li>
 *   <li>离开很短则只在连接确实不健康时才重连,避免无谓抖动。</li>
 * </ul>
 */
const handleResume = () => {
  const now = Date.now();
  channels.forEach((conn) => {
    const hiddenFor = conn.hiddenAt == null ? 0 : now - conn.hiddenAt;
    conn.hiddenAt = null;
    if (hiddenFor >= RESUME_RECONNECT_MIN_HIDDEN_MS) {
      // 后台期间可能被挂起过:这条连接即便"看起来还开着",期间到达的心跳也可能是排队补发的旧消息,
      // 直接换一条新连接,后续数据由重连触发的 onRefresh 拉取
      reconnect(conn, true);
      return;
    }
    reconnectNow(conn);
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

  // 心跳:仅更新存活时间,不触发业务刷新(命名事件不进 onmessage)。
  // 记账用本机墙钟时间戳,不依赖服务端时钟;JS 被挂起期间排队补发的旧心跳由
  // handleResume 里的"离开时长(本机时间戳)"兜底——回到前台直接换新连接,不会被旧心跳骗过。
  es.addEventListener('ping', () => {
    const now = Date.now();
    conn.lastEventAt = now;
    conn.lastLiveAt = now;
    // 先刷新状态(此刻才从"连接中"转为"已连上"),确认连上之后再触发重连补偿刷新
    refreshStatus(conn);
    if (conn.refreshOnConfirm) {
      conn.refreshOnConfirm = false;
      conn.listeners.forEach((l) => {
        try {
          l.onRefresh?.();
        } catch {
          // 单个订阅者异常不影响其他订阅者
        }
      });
    }
  });

  es.onopen = () => {
    conn.lastEventAt = Date.now();
    if (conn.reopened) {
      // 传输层建连成功 ≠ 连接已确认:此时标识还是"连接中",先只标记待刷新,
      // 等首条心跳到达、状态真正转为"已连上"后再触发订阅方重新调接口(见上面的 ping 处理)
      conn.refreshOnConfirm = true;
    }
    conn.reopened = true;
    // 握手成功还不算"确认连上":等首条心跳到达才转绿
    refreshStatus(conn);
  };

  es.onmessage = (e) => {
    // 业务消息不参与存活判定:存活一律由心跳负责(建连即发 + 每 15s 一次),
    // 避免排队补发的旧业务消息把一条其实已经断开的连接伪装成"刚刚还活着"。
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
  window.addEventListener('online', handleResume);
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
      hiddenAt: null,
      status: 'connecting',
      refreshOnConfirm: false
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
