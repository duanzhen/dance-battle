import { SelfieSegmentation } from '@mediapipe/selfie_segmentation';

/**
 * MediaPipe Selfie Segmentation 的全局单例。
 *
 * <p>MediaPipe 的 wasm 运行时在一个页面里只能初始化一次:如果每次组件挂载都 new 一个实例、
 * 卸载时再 close,就会在同一页面里二次加载 loader 脚本并重建一份 wasm + GL 上下文,
 * 典型表现是「第二次自动抠图时崩掉 / 浏览器把标签页重载」。所以实例缓存到模块级,后续挂载
 * 只刷新选项与回调。</p>
 */
let sharedSelfieSegmentation: any = null;

/** 取全局唯一的 SelfieSegmentation 实例(不存在则创建),并同步选项/结果回调 */
export function acquireSelfieSegmentation(
  locateFile: (file: string) => string,
  options: { modelSelection: number },
  onResults: (results: any) => void
): any {
  if (!sharedSelfieSegmentation) {
    sharedSelfieSegmentation = new SelfieSegmentation({ locateFile });
  }
  sharedSelfieSegmentation.setOptions(options);
  sharedSelfieSegmentation.onResults(onResults);
  return sharedSelfieSegmentation;
}
