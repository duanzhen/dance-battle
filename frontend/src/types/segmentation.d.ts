/**
 * MediaPipe Selfie Segmentation 类型定义
 */

declare global {
  interface Window {
    SelfieSegmentation?: any;
  }
}

/**
 * 背景处理选项
 */
export type BackgroundType = 'transparent' | 'solid' | 'blur' | 'original';

/**
 * 图片处理选项
 */
export interface ProcessingOptions {
  /** 背景类型 */
  background: BackgroundType;
  /** 纯色背景颜色（十六进制） */
  solidColor?: string;
  /** 模糊程度（1-20px） */
  blurAmount?: number;
  /** 输出格式 */
  outputFormat?: 'png' | 'jpeg';
  /** 图片质量（0-1） */
  quality?: number;
}

/**
 * 分割结果
 */
export interface SegmentationResult {
  /** 处理后的图片 Blob */
  processedBlob: Blob;
  /** 处理后的图片 URL */
  processedUrl: string;
  /** 原始图片 URL */
  originalUrl: string;
  /** 处理耗时（毫秒） */
  processingTime: number;
}

/**
 * 分割进度
 */
export interface SegmentationProgress {
  /** 当前阶段 */
  stage: 'loading' | 'processing' | 'compositing' | 'complete' | 'error';
  /** 进度百分比（0-100） */
  percent: number;
  /** 进度消息 */
  message: string;
}

/**
 * 分割配置选项
 */
export interface SegmentationConfig {
  /** 模型选择：0=通用，1=横向 */
  modelSelection?: 0 | 1;
  /** 自拍模式：true=前置摄像头，false=后置摄像头 */
  selfieMode?: boolean;
}

export {};
