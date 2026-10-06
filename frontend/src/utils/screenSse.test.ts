import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 屏幕控制通道「多屏共享一条连接」时,新增屏幕必须能刷新连接地址。
 *
 * <p>回归:统一 SSE 客户端只在首次订阅时建连并求值一次 buildUrl,后续屏幕复用旧连接。
 * 于是控制连接 URL 里始终只有第一个屏幕的 screenId,后端只注册了第一个屏幕,
 * 其余屏幕在别的设备上打开大屏会被「屏幕未注册」拒绝(页面显示信号丢失)。
 * 修复后新增屏幕会强制重开连接,buildUrl 带上全部 screenId。</p>
 */

class FakeEventSource {
  static CLOSED = 2;
  static OPEN = 1;
  static instances: FakeEventSource[] = [];

  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((e: any) => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners: Record<string, ((e: any) => void)[]> = {};

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: (e: any) => void) {
    (this.listeners[type] ||= []).push(cb);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }
}

import { subscribeScreenControl, unsubscribeAllScreens } from './screenSse';

const screenIdsOf = (es: FakeEventSource) => {
  const raw = new URL(es.url, 'http://localhost').searchParams.get('screenIds') || '';
  return raw.split(',').filter(Boolean).sort();
};

describe('screenSse 多屏共享连接', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    vi.useFakeTimers();
  });

  afterEach(() => {
    unsubscribeAllScreens();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('首个屏幕只建一条连接,不额外重连', () => {
    subscribeScreenControl('screen-1', '42', 'auth', () => {});
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(screenIdsOf(FakeEventSource.instances[0])).toEqual(['screen-1']);
  });

  it('新增屏幕会重开连接,URL 带上全部 screenId', async () => {
    subscribeScreenControl('screen-1', '42', 'auth', () => {});
    subscribeScreenControl('screen-2', '42', 'auth', () => {});
    subscribeScreenControl('screen-3', '42', 'auth', () => {});
    // 多次登记合并成一次重连(0ms 后执行)
    await vi.runOnlyPendingTimersAsync();

    const last = FakeEventSource.instances.at(-1)!;
    expect(screenIdsOf(last)).toEqual(['screen-1', 'screen-2', 'screen-3']);
  });

  it('同一赛事复用一条连接(不会为每个屏幕各开一条)', () => {
    subscribeScreenControl('screen-1', '42', 'auth', () => {});
    subscribeScreenControl('screen-2', '42', 'auth', () => {});
    // 只有一次因新增屏幕触发的重连,而不是每屏一条连接
    expect(FakeEventSource.instances.length).toBeLessThanOrEqual(2);
  });
});
