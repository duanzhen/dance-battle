<template>
  <div class="matting-container">
    <input ref="fileInputRef" type="file" accept="image/*" @change="handleFileUpload" hidden />

    <div
      class="canvas-wrapper"
      :class="{ 'has-image': hasProcessedImg, 'is-dragging': isFileDragging, 'is-uploading': isUploading }"
      @click="!hasProcessedImg && triggerFileSelect()"
      @dragover.prevent="handleFileDragOver"
      @dragleave.prevent="handleFileDragLeave"
      @drop.prevent="handleFileDrop"
      @wheel.prevent="handleWheel"
    >
      <img ref="sourceImg" :src="imgSrc" @load="onImageLoad" style="display: none" />

      <div v-if="isProcessing" class="loading-overlay">
        <span>AI 正在处理中...</span>
      </div>

      <div v-if="isUploading" class="loading-overlay">
        <span>正在上传...</span>
      </div>

      <!-- 500x500 固定大小画布 -->
      <canvas
        ref="canvasRef"
        width="500"
        height="500"
        @mousedown="hasProcessedImg && handleMouseDown($event)"
        @touchstart.prevent="handleTouchStart"
        @touchmove.prevent="handleTouchMove"
        @touchend="handleTouchEnd"
      ></canvas>

      <div v-if="!hasProcessedImg" class="placeholder">
        <div class="placeholder-icon"><Upload :size="48" /></div>
        <div class="placeholder-text">选择图片</div>
        <div class="placeholder-hint">请选择JPG/PNG 支持拖拽</div>
      </div>

      <div v-if="hasProcessedImg" class="canvas-hint">
        <span class="hidden sm:inline">拖动移动 滚轮缩放</span>
        <span class="sm:hidden">单指拖动 双指缩放</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, getCurrentInstance } from 'vue';
import { Upload } from 'lucide-vue-next';
import { SelfieSegmentation } from '@mediapipe/selfie_segmentation';
import request from '@/utils/request';
import { globalHeaders } from '@/utils/request';

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue']);

const { proxy } = getCurrentInstance();

// 常量
const CANVAS_SIZE = 500;

// --- 响应式状态 ---
const imgSrc = ref('');
const canvasRef = ref(null);
const sourceImg = ref(null);
const fileInputRef = ref(null);
const isProcessing = ref(false);
const isUploading = ref(false);
const isFileDragging = ref(false);
const hasProcessedImg = ref(false);
const modelSelection = ref(1);

// 图片变换状态
const scale = ref(1);
const offsetX = ref(0);
const offsetY = ref(0);

// 改动标记
const hasChanges = ref(false);

// 拖拽状态
const isDragging = ref(false);
const dragStartX = ref(0);
const dragStartY = ref(0);
// 拖拽起始时的画布偏移(画布像素),与屏幕起点配合计算
const dragStartOffsetX = ref(0);
const dragStartOffsetY = ref(0);
// 触屏双指缩放
const pinchStartDist = ref(0);
const pinchStartScale = ref(1);

// --- MediaPipe 实例 ---
let selfieSegmentation = null;
let processedCanvas = null; // 存储抠图后的原始图片
let processedCtx = null;

// --- 初始化 MediaPipe ---
const initMediaPipe = async () => {
  selfieSegmentation = new SelfieSegmentation({
    locateFile: (file) => {
      // 本地加载模型/运行时(public/mediapipe),避免 CDN 被墙
      return `${import.meta.env.BASE_URL}mediapipe/${file}`;
    }
  });

  selfieSegmentation.setOptions({
    modelSelection: modelSelection.value
  });

  selfieSegmentation.onResults(onResults);
};

// --- 加载编辑图片 ---
const loadEditImage = async (url) => {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      // 创建离屏 canvas
      if (!processedCanvas) {
        processedCanvas = document.createElement('canvas');
        processedCtx = processedCanvas.getContext('2d');
      }

      // 编辑模式下，加载的是 500x500 的输出图片
      processedCanvas.width = img.width;
      processedCanvas.height = img.height;

      // 绘制图片到离屏 canvas
      processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);
      processedCtx.drawImage(img, 0, 0);

      // 编辑模式：图片已经是 500x500，直接 1:1 显示，不做缩放和位置调整
      scale.value = 1;
      offsetX.value = 0;
      offsetY.value = 0;

      hasProcessedImg.value = true;
      hasChanges.value = false; // 编辑模式初始状态无改动

      render();
      resolve();
    };

    img.onerror = () => {
      proxy.$modal.msgError('加载图片失败');
      reject(new Error('Failed to load image'));
    };

    img.src = url;
  });
};

// --- 重置位置和缩放 ---
const resetPosition = () => {
  if (!processedCanvas) return;

  // 计算合适的缩放比例，使图片占据约 85% 画布高度
  const targetHeight = CANVAS_SIZE * 0.85;
  let calculatedScale = targetHeight / processedCanvas.height;

  // 确保缩放后宽度也不超过画布
  const maxScaleForWidth = CANVAS_SIZE / processedCanvas.width;
  calculatedScale = Math.min(calculatedScale, maxScaleForWidth);

  scale.value = calculatedScale;

  // 居中
  offsetX.value = (CANVAS_SIZE - processedCanvas.width) / 2;
  offsetY.value = (CANVAS_SIZE - processedCanvas.height) / 2;

  render();
};

// --- 渲染到显示画布 ---
const render = () => {
  if (!canvasRef.value) return;

  const canvas = canvasRef.value;
  const ctx = canvas.getContext('2d');

  // 清空画布
  ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

  if (!hasProcessedImg.value) {
    return;
  }

  // 应用变换（以图片中心为参考点）
  ctx.save();
  // 平移到图片中心在画布上的位置
  ctx.translate(offsetX.value + processedCanvas.width / 2, offsetY.value + processedCanvas.height / 2);
  // 缩放
  ctx.scale(scale.value, scale.value);
  // 平移回去，使图片中心对齐到原点
  ctx.translate(-processedCanvas.width / 2, -processedCanvas.height / 2);

  // 绘制抠图
  ctx.drawImage(processedCanvas, 0, 0);
  ctx.restore();
};

// --- 监听 modelValue 变化，支持编辑模式 ---
watch(
  () => props.modelValue,
  async (newUrl) => {
    if (newUrl && newUrl !== imgSrc.value) {
      // 加载已保存的图片进行编辑
      await loadEditImage(newUrl);
    } else if (!newUrl) {
      // 清空状态
      hasProcessedImg.value = false;
      if (processedCanvas) {
        processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);
      }
      render();
    }
  },
  { immediate: true }
);

// --- 触发文件选择 ---
const triggerFileSelect = () => {
  fileInputRef.value?.click();
};

// --- 文件上传处理 ---
const handleFileUpload = (event) => {
  const file = event.target.files[0];
  handleFile(file);
  // 清空 input，允许重复选择同一文件
  event.target.value = '';
};

// --- 处理单个文件 ---
const handleFile = (file) => {
  if (!file || !file.type.startsWith('image/')) {
    proxy.$modal.msgError('请选择有效的图片文件！');
    return;
  }

  const reader = new FileReader();
  reader.onload = (e) => {
    imgSrc.value = e.target.result;
  };
  reader.readAsDataURL(file);
};

// --- 文件拖拽事件处理 ---
const handleFileDragOver = () => {
  isFileDragging.value = true;
};

const handleFileDragLeave = () => {
  isFileDragging.value = false;
};

const handleFileDrop = (event) => {
  isFileDragging.value = false;
  const file = event.dataTransfer.files[0];
  handleFile(file);
};

// --- 检测图片是否已经是透明背景 ---
const checkTransparentBackground = (img) => {
  return new Promise((resolve) => {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    canvas.width = img.width;
    canvas.height = img.height;

    ctx.drawImage(img, 0, 0);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const pixels = imageData.data;

    // 检查边缘区域的透明度（边缘通常更容易有背景）
    const edgeWidth = Math.min(50, Math.floor(canvas.width / 4));
    const edgeHeight = Math.min(50, Math.floor(canvas.height / 4));

    let transparentPixels = 0;
    let totalEdgePixels = 0;

    // 遍历边缘像素
    for (let y = 0; y < canvas.height; y++) {
      for (let x = 0; x < canvas.width; x++) {
        const isEdge = x < edgeWidth || x >= canvas.width - edgeWidth || y < edgeHeight || y >= canvas.height - edgeHeight;

        if (isEdge) {
          const alphaIndex = (y * canvas.width + x) * 4 + 3;
          totalEdgePixels++;
          if (pixels[alphaIndex] < 128) {
            transparentPixels++;
          }
        }
      }
    }

    // 如果边缘透明像素超过 30%，认为是透明背景图
    const transparentRatio = transparentPixels / totalEdgePixels;
    resolve(transparentRatio > 0.3);
  });
};

// --- 图片加载完毕，开始处理 ---
const onImageLoad = async () => {
  // 先检测是否已经是透明背景
  const isTransparent = await checkTransparentBackground(sourceImg.value);

  if (isTransparent) {
    // 已经是透明背景，直接使用原图
    loadOriginalImage();
  } else {
    // 需要抠图处理
    processImage();
  }
};

// --- 直接加载原图（跳过 AI 处理） ---
const loadOriginalImage = () => {
  if (!sourceImg.value) return;

  // 创建离屏 canvas
  if (!processedCanvas) {
    processedCanvas = document.createElement('canvas');
    processedCtx = processedCanvas.getContext('2d');
  }

  processedCanvas.width = sourceImg.value.width;
  processedCanvas.height = sourceImg.value.height;

  // 直接绘制原图
  processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);
  processedCtx.drawImage(sourceImg.value, 0, 0);

  // 重置位置和缩放
  resetPosition();
  hasProcessedImg.value = true;
  hasChanges.value = true;

  // 初始渲染
  render();
};

// --- 执行抠图逻辑 ---
const processImage = async () => {
  if (!sourceImg.value || !selfieSegmentation) return;

  isProcessing.value = true;

  selfieSegmentation.setOptions({
    modelSelection: modelSelection.value
  });

  await selfieSegmentation.send({ image: sourceImg.value });
};

// --- AI 处理回调 ---
const onResults = (results) => {
  // 创建离屏 canvas 保存抠图结果
  if (!processedCanvas) {
    processedCanvas = document.createElement('canvas');
    processedCtx = processedCanvas.getContext('2d');
  }

  processedCanvas.width = results.image.width;
  processedCanvas.height = results.image.height;

  processedCtx.save();
  processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);

  // 1. 绘制分割掩码
  processedCtx.drawImage(results.segmentationMask, 0, 0, processedCanvas.width, processedCanvas.height);

  // 2. 使用 source-in 混合模式
  processedCtx.globalCompositeOperation = 'source-in';

  // 3. 绘制原始图片
  processedCtx.drawImage(results.image, 0, 0, processedCanvas.width, processedCanvas.height);

  processedCtx.restore();

  // 重置位置和缩放
  resetPosition();
  hasProcessedImg.value = true;
  hasChanges.value = true; // 新上传的图片，有改动
  isProcessing.value = false;

  // 初始渲染
  render();
};

// --- 鼠标拖拽事件 ---
/** 画布显示缩放系数:画布 CSS 宽度 / 逻辑尺寸(500),移动端自适应后坐标需按此换算 */
const displayScale = () => {
  const canvas = canvasRef.value;
  if (!canvas) return 1;
  const rect = canvas.getBoundingClientRect();
  return rect.width > 0 ? rect.width / CANVAS_SIZE : 1;
};

const handleMouseDown = (event) => {
  if (!hasProcessedImg.value) return;

  isDragging.value = true;
  dragStartX.value = event.clientX;
  dragStartY.value = event.clientY;
  dragStartOffsetX.value = offsetX.value;
  dragStartOffsetY.value = offsetY.value;

  document.addEventListener('mousemove', handleMouseMove);
  document.addEventListener('mouseup', handleMouseUp);
};

const handleMouseMove = (event) => {
  if (!isDragging.value) return;

  const s = displayScale();
  offsetX.value = dragStartOffsetX.value + (event.clientX - dragStartX.value) / s;
  offsetY.value = dragStartOffsetY.value + (event.clientY - dragStartY.value) / s;

  hasChanges.value = true; // 拖拽位置，有改动
  render();
};

const handleMouseUp = () => {
  isDragging.value = false;
  document.removeEventListener('mousemove', handleMouseMove);
  document.removeEventListener('mouseup', handleMouseUp);
};

// --- 触屏拖拽 / 双指缩放 ---
const touchDist = (touches) => {
  if (!touches || touches.length < 2) return 0;
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.sqrt(dx * dx + dy * dy);
};

const handleTouchStart = (event) => {
  if (!hasProcessedImg.value) return;
  const touches = event.touches;
  if (touches.length === 1) {
    isDragging.value = true;
    dragStartX.value = touches[0].clientX;
    dragStartY.value = touches[0].clientY;
    dragStartOffsetX.value = offsetX.value;
    dragStartOffsetY.value = offsetY.value;
  } else if (touches.length === 2) {
    pinchStartDist.value = touchDist(touches);
    pinchStartScale.value = scale.value;
  }
};

const handleTouchMove = (event) => {
  if (!hasProcessedImg.value) return;
  const touches = event.touches;
  if (touches.length === 1 && isDragging.value) {
    const s = displayScale();
    offsetX.value = dragStartOffsetX.value + (touches[0].clientX - dragStartX.value) / s;
    offsetY.value = dragStartOffsetY.value + (touches[0].clientY - dragStartY.value) / s;
    hasChanges.value = true;
    render();
  } else if (touches.length === 2 && pinchStartDist.value > 0) {
    const d = touchDist(touches);
    if (d > 0) {
      scale.value = Math.max(0.1, Math.min(3, pinchStartScale.value * (d / pinchStartDist.value)));
      hasChanges.value = true;
      render();
    }
  }
};

const handleTouchEnd = () => {
  isDragging.value = false;
  pinchStartDist.value = 0;
};

// --- 滚轮缩放事件 ---
const handleWheel = (event) => {
  if (!hasProcessedImg.value) return;

  const delta = event.deltaY > 0 ? -0.01 : 0.01;
  scale.value = Math.max(0.1, Math.min(3, scale.value + delta));

  hasChanges.value = true; // 缩放，有改动
  render();
};

// --- 上传图片到服务器(本地直传) ---
const uploadToServer = async (blob) => {
  try {
    const formData = new FormData();
    formData.append('file', blob, 'avatar.png');
    const res = await request({
      url: '/resource/oss/upload',
      method: 'post',
      headers: {
        ...globalHeaders()
      },
      data: formData
    });
    if (res.code !== 200 || !res.data?.url) {
      throw new Error(res?.msg || '上传失败');
    }
    // 返回文件 URL
    return { url: res.data.url };
  } catch (error) {
    throw error;
  }
};

// --- 导出图片 ---
const exportImage = async () => {
  if (!canvasRef.value || !hasProcessedImg.value) {
    proxy.$modal.msgWarning('请先上传并处理图片！');
    return null;
  }

  // 如果没有改动，直接返回现有 URL
  if (!hasChanges.value && props.modelValue) {
    return props.modelValue;
  }

  try {
    isUploading.value = true;

    // 将 canvas 转为 blob
    const blob = await new Promise((resolve) => {
      canvasRef.value.toBlob(resolve, 'image/png');
    });

    // 上传到服务器
    const result = await uploadToServer(blob);

    // 返回 url
    emit('update:modelValue', result.url);
    hasChanges.value = false; // 上传后重置改动标记

    return result.url;
  } catch (error) {
    proxy.$modal.msgError(error.message || '上传失败');
    return null;
  } finally {
    isUploading.value = false;
  }
};

// --- 对外暴露方法 ---
defineExpose({
  exportImage,
  hasProcessedImg
});

// --- 生命周期 ---
onMounted(() => {
  initMediaPipe();
});

onBeforeUnmount(() => {
  if (selfieSegmentation) {
    selfieSegmentation.close();
  }
});
</script>

<style scoped>
.matting-container {
  width: 100%;
  max-width: 350px;
  margin: 0 auto;
  font-family: sans-serif;
  border: 1px solid #eee;
  padding: 16px;
  border-radius: 8px;
  box-sizing: border-box;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.canvas-wrapper {
  position: relative;
  width: 100%;
  max-width: 300px;
  aspect-ratio: 1 / 1;
  margin: 0 auto;
  border: 2px dashed #ccc;
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  transition: all 0.3s ease;
}

/* 无图片时的样式 */
.canvas-wrapper:not(.has-image) {
  background-color: #2a2a2a;
  background-image:
    linear-gradient(45deg, #3a3a3a 25%, transparent 25%), linear-gradient(-45deg, #3a3a3a 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #3a3a3a 75%), linear-gradient(-45deg, transparent 75%, #3a3a3a 75%);
  background-size: 20px 20px;
  background-position:
    0 0,
    0 10px,
    10px -10px,
    -10px 0px;
  cursor: pointer;
}

.canvas-wrapper:not(.has-image):hover {
  border-color: #3498db;
  background-color: rgba(52, 152, 219, 0.05);
}

.canvas-wrapper.is-dragging {
  border-color: #2ecc71;
  background-color: rgba(46, 204, 113, 0.1);
  border-style: solid;
}

/* 有图片时的样式 */
.canvas-wrapper.has-image {
  cursor: grab;
  border-style: solid;
}

.canvas-wrapper.has-image:active {
  cursor: grabbing;
}

canvas {
  display: block;
  width: 100%;
  height: 100%;
  background-color: #1a1a1a;
  background-image:
    linear-gradient(45deg, #2a2a2a 25%, transparent 25%), linear-gradient(-45deg, #2a2a2a 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #2a2a2a 75%), linear-gradient(-45deg, transparent 75%, #2a2a2a 75%);
  background-size: 20px 20px;
  background-position:
    0 0,
    0 10px,
    10px -10px,
    -10px 0px;
}

/* 无图片时 canvas 不响应鼠标事件，让点击穿透到父容器 */
.canvas-wrapper:not(.has-image) canvas {
  pointer-events: none;
}

.placeholder {
  position: absolute;
  text-align: center;
  padding: 40px 20px;
  pointer-events: none;
}

.placeholder-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.placeholder-text {
  font-size: 18px;
  font-weight: 500;
  color: #ddd;
  margin-bottom: 8px;
}

.placeholder-hint {
  font-size: 14px;
  color: #aaa;
}

.canvas-hint {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.35);
  color: white;
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  pointer-events: none;
  user-select: none;
}

.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 10;
  font-weight: bold;
  color: #fff;
}
</style>
