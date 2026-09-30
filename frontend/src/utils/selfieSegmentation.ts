import { SelfieSegmentation } from '@mediapipe/selfie_segmentation';

/**
 * MediaPipe Selfie Segmentation 的全局单例。
 *
 * <p>MediaPipe 的 wasm 运行时在一个页面里只能初始化一次:如果每次组件挂载都 new 一个实例、
 * 卸载时再 close,就会在同一页面里二次加载 loader 脚本并重建一份 wasm + GL 上下文,
 * 典型表现是「第二次自动抠图时崩掉 / 浏览器把标签页重载」。所以实例缓存到模块级,后续挂载
 * 只刷新选项与回调。</p>
 *
 * <p>回调改成「一次注册、多订阅者分发」:实例上的 onResults 只会保留最后一次注册的函数,
 * 多个组件各注册一次就会互相顶掉;而且任何一次回调里抛错都会顺着 MediaPipe 的调用栈
 * 把运行时带崩。这里只在实例上挂一个转发器,逐个 try/catch 分发给订阅者。</p>
 */
export type SelfieSegmentationResultsHandler = (results: any) => void;

let sharedSelfieSegmentation: any = null;
const resultsHandlers = new Set<SelfieSegmentationResultsHandler>();

/** 本地加载模型/运行时(public/mediapipe),避免 CDN 被墙或离线部署时加载失败 */
function defaultLocateFile(file: string): string {
  const base = import.meta.env.BASE_URL || '/';
  return `${base}mediapipe/${file}`;
}

/** 创建(或在已有实例上补挂)唯一的转发回调 */
function ensureInstance(): any {
  if (!sharedSelfieSegmentation) {
    sharedSelfieSegmentation = new SelfieSegmentation({ locateFile: defaultLocateFile });
    sharedSelfieSegmentation.onResults((results: any) => {
      resultsHandlers.forEach((handler) => {
        try {
          handler(results);
        } catch (e) {
          console.error('[selfieSegmentation] onResults 回调异常:', e);
        }
      });
    });
  }
  return sharedSelfieSegmentation;
}

/**
 * 取全局唯一的 SelfieSegmentation 实例(不存在则创建),同步选项并订阅结果回调。
 *
 * @returns 实例句柄,可直接 setOptions / send;不要再对它调用 onResults(会覆盖转发器)
 */
export function acquireSelfieSegmentation(
  options: { modelSelection?: number; selfieMode?: boolean } = {},
  onResults?: SelfieSegmentationResultsHandler
): any {
  const instance = ensureInstance();
  if (options) {
    instance.setOptions(options);
  }
  if (onResults) {
    resultsHandlers.add(onResults);
  }
  return instance;
}

/**
 * 退订结果回调(组件卸载时调用)。
 *
 * <p>只解绑回调、不 close 实例:实例整页复用,close 掉就再也起不来了。</p>
 */
export function releaseSelfieSegmentation(onResults?: SelfieSegmentationResultsHandler): void {
  if (onResults) {
    resultsHandlers.delete(onResults);
  }
}
