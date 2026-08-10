/**
 * 图片处理工具函数
 * 用于 Canvas 操作、图片合成、遮罩应用
 */

import type { ProcessingOptions } from '@/types/segmentation';

/**
 * 图片验证结果
 */
interface ValidationResult {
  valid: boolean;
  error?: string;
}

/**
 * 加载图片到 HTMLImageElement
 * @param source - 图片来源（URL 或 File）
 * @returns Promise<HTMLImageElement>
 */
export async function loadImage(source: string | File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();

    img.onload = () => {
      resolve(img);
    };
    img.onerror = () => reject(new Error('Failed to load image'));

    if (typeof source === 'string') {
      img.src = source;
      // 处理 CORS 问题
      img.crossOrigin = 'anonymous';
    } else {
      const url = URL.createObjectURL(source);
      img.src = url;
      // 注意：不在这里清理 URL，让调用者管理
      // 因为 MediaPipe 需要在处理过程中保持图片有效
    }
  });
}

/**
 * 将图片转换为 Canvas
 * @param image - 图片元素
 * @returns HTMLCanvasElement
 */
export function imageToCanvas(image: HTMLImageElement): HTMLCanvasElement {
  const canvas = document.createElement('canvas');
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Failed to get canvas context');
  }

  ctx.drawImage(image, 0, 0);
  return canvas;
}

/**
 * 调整图片大小（保持宽高比）
 * @param image - 原始图片
 * @param maxDimension - 最大边长
 * @returns 调整后的 Canvas
 */
export function resizeImage(image: HTMLImageElement, maxDimension: number): HTMLCanvasElement {
  const { naturalWidth, naturalHeight } = image;
  let width = naturalWidth;
  let height = naturalHeight;

  // 计算缩放比例
  if (width > maxDimension || height > maxDimension) {
    const ratio = Math.min(maxDimension / width, maxDimension / height);
    width = Math.round(width * ratio);
    height = Math.round(height * ratio);
  }

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Failed to get canvas context');
  }

  // 使用高质量缩放
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(image, 0, 0, width, height);

  return canvas;
}

/**
 * 应用高斯模糊
 * @param imageData - 原始图片数据
 * @param canvas - 画布
 * @param blurAmount - 模糊程度（1-20）
 */
export function applyGaussianBlur(imageData: ImageData, canvas: HTMLCanvasElement, blurAmount: number): void {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  // 使用 Canvas filter API 进行模糊
  ctx.filter = `blur(${blurAmount}px)`;
  ctx.putImageData(imageData, 0, 0);
}

/**
 * 将十六进制颜色转为 RGBA
 * @param hex - 十六进制颜色
 * @returns RGBA 字符串
 */
function hexToRgba(hex: string): string {
  // 移除 # 号
  const cleanHex = hex.replace('#', '');

  // 解析 RGB
  let r: number, g: number, b: number;
  if (cleanHex.length === 3) {
    r = parseInt(cleanHex[0] + cleanHex[0], 16);
    g = parseInt(cleanHex[1] + cleanHex[1], 16);
    b = parseInt(cleanHex[2] + cleanHex[2], 16);
  } else if (cleanHex.length === 6) {
    r = parseInt(cleanHex.substring(0, 2), 16);
    g = parseInt(cleanHex.substring(2, 4), 16);
    b = parseInt(cleanHex.substring(4, 6), 16);
  } else {
    return 'rgba(255, 255, 255, 1)';
  }

  return `rgba(${r}, ${g}, ${b}, 1)`;
}

/**
 * 应用遮罩和背景处理
 * @param sourceCanvas - 源画布
 * @param mask - 分割遮罩（ImageData 或 null）
 * @param originalCanvas - 原始图片画布（用于模糊背景）
 * @param options - 处理选项
 * @returns 处理后的 Canvas
 */
export function applyMaskWithBackground(
  sourceCanvas: HTMLCanvasElement,
  mask: ImageData | null,
  originalCanvas: HTMLCanvasElement,
  options: ProcessingOptions
): HTMLCanvasElement {
  const { background, solidColor = '#ffffff', blurAmount = 10 } = options;

  // 如果保留原背景，直接返回
  if (background === 'original') {
    return sourceCanvas;
  }

  const resultCanvas = document.createElement('canvas');
  resultCanvas.width = sourceCanvas.width;
  resultCanvas.height = sourceCanvas.height;

  const ctx = resultCanvas.getContext('2d');
  if (!ctx) {
    throw new Error('Failed to get result canvas context');
  }

  const width = sourceCanvas.width;
  const height = sourceCanvas.height;

  // 绘制背景
  if (background === 'transparent') {
    // 透明背景，无需绘制
  } else if (background === 'solid') {
    // 纯色背景
    ctx.fillStyle = hexToRgba(solidColor);
    ctx.fillRect(0, 0, width, height);
  } else if (background === 'blur') {
    // 模糊背景
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = width;
    tempCanvas.height = height;
    const tempCtx = tempCanvas.getContext('2d');
    if (tempCtx) {
      tempCtx.filter = `blur(${blurAmount}px)`;
      tempCtx.drawImage(originalCanvas, 0, 0, width, height);
      ctx.drawImage(tempCanvas, 0, 0);
    }
  }

  // 如果有遮罩，应用遮罩
  if (mask) {
    const sourceData = sourceCanvas.getContext('2d')?.getImageData(0, 0, width, height);
    if (!sourceData) {
      return resultCanvas;
    }

    const maskData = mask.data;
    const sourcePixels = sourceData.data;
    const resultData = ctx.getImageData(0, 0, width, height);
    const resultPixels = resultData.data;

    // 遍历像素，应用遮罩
    for (let i = 0; i < maskData.length; i += 4) {
      const maskValue = maskData[i] / 255; // 归一化遮罩值

      // 如果遮罩值为 1（人物），使用源像素
      // 如果遮罩值为 0（背景），保持透明或背景色
      if (maskValue > 0.5) {
        // 人物区域
        const pixelIndex = i;
        resultPixels[pixelIndex] = sourcePixels[pixelIndex]; // R
        resultPixels[pixelIndex + 1] = sourcePixels[pixelIndex + 1]; // G
        resultPixels[pixelIndex + 2] = sourcePixels[pixelIndex + 2]; // B
        resultPixels[pixelIndex + 3] = sourcePixels[pixelIndex + 3] * maskValue; // A
      }
    }

    ctx.putImageData(resultData, 0, 0);
  } else {
    // 没有遮罩，直接绘制原图
    ctx.drawImage(sourceCanvas, 0, 0);
  }

  return resultCanvas;
}

/**
 * 将 Canvas 转换为 Blob
 * @param canvas - 画布
 * @param quality - 图片质量（0-1）
 * @returns Promise<Blob>
 */
export function canvasToBlob(canvas: HTMLCanvasElement, quality = 0.9): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) {
          resolve(blob);
        } else {
          reject(new Error('Failed to convert canvas to blob'));
        }
      },
      'image/png',
      quality
    );
  });
}

/**
 * 验证图片文件
 * @param file - 文件对象
 * @returns 验证结果
 */
export function validateImageFile(file: File): ValidationResult {
  // 检查文件类型
  const validTypes = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp'];
  if (!validTypes.includes(file.type)) {
    return {
      valid: false,
      error: '不支持的图片格式，请上传 JPG、PNG 或 WebP 格式的图片'
    };
  }

  // 检查文件大小（限制 10MB）
  const maxSize = 10 * 1024 * 1024;
  if (file.size > maxSize) {
    return {
      valid: false,
      error: '图片过大，请使用小于 10MB 的图片'
    };
  }

  // 检查文件是否为空
  if (file.size === 0) {
    return {
      valid: false,
      error: '图片文件为空，请重新选择'
    };
  }

  return { valid: true };
}

/**
 * 创建预览 URL
 * @param blob - Blob 对象
 * @returns Object URL
 */
export function createPreviewUrl(blob: Blob): string {
  return URL.createObjectURL(blob);
}

/**
 * 释放 Object URL
 * @param url - Object URL
 */
export function revokePreviewUrl(url: string): void {
  URL.revokeObjectURL(url);
}

/**
 * 压缩图片
 * @param file - 原始文件
 * @param maxSize - 目标大小（KB）
 * @returns Promise<Blob>
 */
export async function compressImage(file: File, maxSize = 500): Promise<Blob> {
  // 如果使用 image-conversion 库
  try {
    const { compressAccurately } = await import('image-conversion');
    return await compressAccurately(file, maxSize);
  } catch (error) {
    console.error('Image compression failed:', error);
    // 降级：返回原文件
    return file;
  }
}
