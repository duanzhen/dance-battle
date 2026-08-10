/**
 * MediaPipe Selfie Segmentation Composable
 * 用于人像抠图和背景移除
 */

import { ref, Ref, shallowRef } from 'vue';
import { SelfieSegmentation } from '@mediapipe/selfie_segmentation';
import type { ProcessingOptions, SegmentationResult, SegmentationConfig } from '@/types/segmentation';
import {
  loadImage,
  resizeImage,
  imageToCanvas,
  applyMaskWithBackground,
  canvasToBlob,
  createPreviewUrl,
  validateImageFile
} from '@/utils/imageProcessing';

/**
 * Composable 返回值接口
 */
export interface UseSelfieSegmentationReturn {
  /** MediaPipe 模型是否已加载 */
  isLoaded: Ref<boolean>;
  /** 是否正在加载模型 */
  isLoading: Ref<boolean>;
  /** 是否正在处理图片 */
  isProcessing: Ref<boolean>;
  /** 处理进度（0-100） */
  progress: Ref<number>;
  /** 错误信息 */
  error: Ref<string | null>;
  /** 加载 MediaPipe 模型 */
  loadSegmentation: (config?: SegmentationConfig) => Promise<void>;
  /** 处理图片 */
  processImage: (imageFile: File, options: ProcessingOptions) => Promise<SegmentationResult>;
  /** 重置状态 */
  reset: () => void;
}

// 全局单例 - 避免重复加载模型
let segmenterInstance: SelfieSegmentation | null = null;
let loadingPromise: Promise<void> | null = null;

/**
 * MediaPipe Selfie Segmentation Composable
 */
export function useSelfieSegmentation(): UseSelfieSegmentationReturn {
  const isLoaded = ref(false);
  const isLoading = ref(false);
  const isProcessing = ref(false);
  const progress = ref(0);
  const error = ref<string | null>(null);

  // 使用 shallowRef 存储 MediaPipe 实例，避免深层响应
  const segmenter = shallowRef<SelfieSegmentation | null>(null);

  // 存储当前处理的状态
  const processingState = ref<{
    resolve: ((value: SegmentationResult) => void) | null;
    reject: ((error: Error) => void) | null;
    originalImage: HTMLImageElement | null;
    resizedCanvas: HTMLCanvasElement | null;
    originalUrl: string | null;
    imageUrl: string | null;
    options: ProcessingOptions | null;
    startTime: number;
  }>({
    resolve: null,
    reject: null,
    originalImage: null,
    resizedCanvas: null,
    originalUrl: null,
    imageUrl: null,
    options: null,
    startTime: 0
  });

  /**
   * 加载 MediaPipe Selfie Segmentation 模型
   */
  const loadSegmentation = async (config: SegmentationConfig = {}): Promise<void> => {
    // 如果已经加载，直接返回
    if (segmenterInstance) {
      segmenter.value = segmenterInstance;
      isLoaded.value = true;
      return;
    }

    // 如果正在加载，等待加载完成
    if (loadingPromise) {
      await loadingPromise;
      return;
    }

    // 开始加载
    loadingPromise = (async () => {
      try {
        isLoading.value = true;
        error.value = null;
        progress.value = 0;

        // 更新进度：开始加载
        progress.value = 10;

        console.log('[useSelfieSegmentation] Starting to load MediaPipe model...');

        // 创建 SelfieSegmentation 实例
        const selfieSegmentation = new SelfieSegmentation({
          locateFile: (file: string) => {
            const path = `https://cdn.jsdelivr.net/npm/@mediapipe/selfie_segmentation@0.1.1675465747/${file}`;
            console.log('[useSelfieSegmentation] Loading file:', path);
            return path;
          }
        });

        progress.value = 30;

        // 设置结果回调
        selfieSegmentation.onResults((results: any) => {
          console.log('[useSelfieSegmentation] onResults called', results ? 'with results' : 'without results');

          const state = processingState.value;
          if (!state.resolve || !state.originalImage) {
            console.warn('[useSelfieSegmentation] No active processing context');
            return;
          }

          try {
            // 更新进度
            progress.value = 70;

            // 检查分割结果
            if (!results.segmentationMask) {
              if (state.reject) {
                state.reject(new Error('分割失败，未检测到有效遮罩'));
              }
              return;
            }

            // 创建原始图片的 Canvas（用于模糊背景）
            const originalCanvas = imageToCanvas(state.originalImage);

            // 应用背景处理
            const processedCanvas = applyMaskWithBackground(
              state.resizedCanvas!,
              results.segmentationMask,
              originalCanvas,
              state.options!
            );

            // 更新进度
            progress.value = 90;

            // 转换为 Blob
            const quality = state.options?.quality ?? 0.9;
            canvasToBlob(processedCanvas, quality).then((processedBlob) => {
              const processedUrl = createPreviewUrl(processedBlob);
              progress.value = 100;

              const processingTime = Date.now() - state.startTime;

              console.log(`[useSelfieSegmentation] Image processed in ${processingTime}ms`);

              // 返回结果
              if (state.resolve) {
                state.resolve({
                  processedBlob,
                  processedUrl,
                  originalUrl: state.originalUrl!,
                  processingTime
                });
              }

              // 清理状态
              if (state.imageUrl && state.imageUrl.startsWith('blob:')) {
                URL.revokeObjectURL(state.imageUrl);
              }
              processingState.value = {
                resolve: null,
                reject: null,
                originalImage: null,
                resizedCanvas: null,
                originalUrl: null,
                imageUrl: null,
                options: null,
                startTime: 0
              };
            }).catch((err) => {
              console.error('[useSelfieSegmentation] Canvas to blob failed:', err);
              if (state.reject) {
                state.reject(new Error(`Failed to convert canvas to blob: ${err}`));
              }
            });
          } catch (err) {
            console.error('[useSelfieSegmentation] Error in onResults:', err);
            if (state.reject) {
              state.reject(err instanceof Error ? err : new Error(String(err)));
            }
          }
        });

        progress.value = 50;

        // 初始化模型
        console.log('[useSelfieSegmentation] Initializing model...');
        await selfieSegmentation.initialize();

        progress.value = 100;

        // 保存实例到全局
        segmenterInstance = selfieSegmentation;
        segmenter.value = selfieSegmentation;
        isLoaded.value = true;

        console.log('[useSelfieSegmentation] Model loaded successfully');
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Unknown error';
        error.value = `加载模型失败: ${errorMessage}`;
        console.error('[useSelfieSegmentation] Failed to load model:', err);
        throw err;
      } finally {
        isLoading.value = false;
        loadingPromise = null;
      }
    })();

    await loadingPromise;
  };

  /**
   * 处理图片并移除背景
   */
  const processImage = async (
    imageFile: File,
    options: ProcessingOptions
  ): Promise<SegmentationResult> => {
    // 验证文件
    const validation = validateImageFile(imageFile);
    if (!validation.valid) {
      throw new Error(validation.error);
    }

    // 确保模型已加载
    if (!segmenter.value || !isLoaded.value) {
      throw new Error('模型未加载，请先调用 loadSegmentation()');
    }

    const startTime = Date.now();
    isProcessing.value = true;
    error.value = null;
    progress.value = 0;

    let tempImageUrl: string | null = null;

    try {
      // 步骤 1: 加载图片
      progress.value = 10;
      console.log('[useSelfieSegmentation] Loading image...');

      // 为 File 创建临时 URL
      tempImageUrl = URL.createObjectURL(imageFile);
      const originalImage = await loadImage(tempImageUrl);
      const originalUrl = createPreviewUrl(imageFile);

      progress.value = 20;

      // 步骤 2: 创建调整大小的 Canvas（用于最终输出）
      const MAX_DIMENSION = 1920;
      const resizedCanvas = resizeImage(originalImage, MAX_DIMENSION);

      progress.value = 30;

      // 步骤 3: 创建 Promise 等待处理结果
      const result = await new Promise<SegmentationResult>((resolve, reject) => {
        // 设置处理状态
        processingState.value = {
          resolve,
          reject,
          originalImage,
          resizedCanvas,
          originalUrl,
          imageUrl: tempImageUrl,
          options,
          startTime
        };

        // 发送到 MediaPipe 进行分割
        progress.value = 50;
        console.log('[useSelfieSegmentation] Sending image to MediaPipe...');

        // 使用 send 方法发送图片
        (segmenter.value! as any).send({ image: originalImage })
          .then(() => {
            console.log('[useSelfieSegmentation] Send completed, waiting for onResults...');
          })
          .catch((err: any) => {
            console.error('[useSelfieSegmentation] Send failed:', err);
            // 清理临时 URL
            if (tempImageUrl && tempImageUrl.startsWith('blob:')) {
              URL.revokeObjectURL(tempImageUrl);
            }
            reject(new Error(`MediaPipe processing failed: ${err?.message || String(err)}`));
          });
      });

      return result;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Unknown error';
      error.value = `图片处理失败: ${errorMessage}`;
      console.error('[useSelfieSegmentation] Failed to process image:', err);
      throw err;
    } finally {
      isProcessing.value = false;
      // 清理上下文（如果在 Promise 中未被清理）
      if (processingState.value.resolve) {
        if (processingState.value.imageUrl && processingState.value.imageUrl.startsWith('blob:')) {
          URL.revokeObjectURL(processingState.value.imageUrl);
        }
        processingState.value = {
          resolve: null,
          reject: null,
          originalImage: null,
          resizedCanvas: null,
          originalUrl: null,
          imageUrl: null,
          options: null,
          startTime: 0
        };
      }
    }
  };

  /**
   * 重置状态（不清理模型实例）
   */
  const reset = (): void => {
    isProcessing.value = false;
    progress.value = 0;
    error.value = null;
  };

  /**
   * 完全清理（包括模型实例）
   * 注意：这会卸载模型，下次使用需要重新加载
   */
  const dispose = (): void => {
    reset();
    segmenter.value = null;
    segmenterInstance = null;
    isLoaded.value = false;
  };

  return {
    isLoaded,
    isLoading,
    isProcessing,
    progress,
    error,
    loadSegmentation,
    processImage,
    reset
  };
}

/**
 * 导出 dispose 函数用于完全清理
 */
export function disposeSelfieSegmentation(): void {
  if (segmenterInstance) {
    try {
      segmenterInstance.close?.();
    } catch (e) {
      console.error('[useSelfieSegmentation] Error closing instance:', e);
    }
    segmenterInstance = null;
  }
}
